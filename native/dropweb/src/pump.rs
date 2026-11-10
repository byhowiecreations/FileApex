//! The download loop: reads the next piece from the connection while the previous piece is still being written, so the network
//! and the disk take turns less. Kept free of browser types so it can be tested on the host with a fake source and sink.

use futures_util::future::join;

pub const CHUNK: usize = 256 * 1024;

pub trait Source {
    /// Reads up to `buf.len()` bytes; `Ok(0)` means the stream ended.
    async fn read(&mut self, buf: &mut [u8]) -> Result<usize, String>;
}

pub trait Sink {
    async fn write(&mut self, data: &[u8]) -> Result<(), String>;
}

/// Moves exactly `size` bytes from `source` to `sink`, in order. Never asks the source for more than is left.
pub async fn pump<S: Source, K: Sink>(source: &mut S, sink: &mut K, size: u64) -> Result<(), String> {
    let mut unread = size;
    let mut current = vec![0u8; CHUNK];
    let mut ahead = vec![0u8; CHUNK];
    let mut current_len = if unread > 0 { read_piece(source, &mut current, &mut unread).await? } else { 0 };
    while current_len > 0 {
        let want = CHUNK.min(unread as usize);
        if want == 0 {
            sink.write(&current[..current_len]).await?;
            break;
        }
        // Write this piece while the next one is read. The write is polled first so a failing disk stops the transfer at once.
        let (written, read) = join(sink.write(&current[..current_len]), read_piece(source, &mut ahead[..want], &mut unread)).await;
        written?;
        current_len = read?;
        std::mem::swap(&mut current, &mut ahead);
    }
    Ok(())
}

/// Reads one piece into `buf` and takes it off `unread`. A stream that ends before the size is reached is an error.
async fn read_piece<S: Source>(source: &mut S, buf: &mut [u8], unread: &mut u64) -> Result<usize, String> {
    let want = buf.len().min(*unread as usize);
    let n = source.read(&mut buf[..want]).await?;
    if n == 0 {
        return Err("connection lost: ended early".into());
    }
    *unread -= n as u64;
    Ok(n)
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_executor::block_on;
    use std::cell::Cell;
    use std::rc::Rc;

    struct Bytes {
        data: Vec<u8>,
        at: usize,
        per_read: usize,
        asked: Rc<Cell<usize>>,
        largest_request: usize,
    }

    impl Bytes {
        fn new(len: usize, per_read: usize) -> Self {
            Self { data: (0..len).map(|i| (i % 251) as u8).collect(), at: 0, per_read, asked: Rc::new(Cell::new(0)), largest_request: 0 }
        }
    }

    impl Source for Bytes {
        async fn read(&mut self, buf: &mut [u8]) -> Result<usize, String> {
            self.asked.set(self.asked.get() + 1);
            self.largest_request = self.largest_request.max(buf.len());
            let n = buf.len().min(self.per_read).min(self.data.len() - self.at);
            buf[..n].copy_from_slice(&self.data[self.at..self.at + n]);
            self.at += n;
            Ok(n)
        }
    }

    #[derive(Default)]
    struct Collect {
        got: Vec<u8>,
        pieces: usize,
    }

    impl Sink for Collect {
        async fn write(&mut self, data: &[u8]) -> Result<(), String> {
            self.got.extend_from_slice(data);
            self.pieces += 1;
            Ok(())
        }
    }

    #[test]
    fn every_byte_arrives_in_order_for_awkward_sizes() {
        for size in [0usize, 1, 1000, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + 17] {
            for per_read in [1000usize, CHUNK, 7 * CHUNK] {
                let mut source = Bytes::new(size, per_read);
                let expected = source.data.clone();
                let mut sink = Collect::default();
                block_on(pump(&mut source, &mut sink, size as u64)).unwrap_or_else(|e| panic!("size {size} read {per_read}: {e}"));
                assert_eq!(sink.got, expected, "size {size} read {per_read}");
            }
        }
    }

    #[test]
    fn it_never_asks_for_more_than_is_left() {
        let mut source = Bytes::new(CHUNK + 10, CHUNK);
        source.data.extend_from_slice(&[9u8; 5000]);
        let mut sink = Collect::default();
        block_on(pump(&mut source, &mut sink, (CHUNK + 10) as u64)).unwrap();
        assert_eq!(sink.got.len(), CHUNK + 10);
        assert!(source.largest_request <= CHUNK);
        assert_eq!(source.at, CHUNK + 10, "bytes beyond the size must stay unread");
    }

    #[test]
    fn a_stream_that_ends_early_is_an_error() {
        let mut source = Bytes::new(100, 100);
        let mut sink = Collect::default();
        let error = block_on(pump(&mut source, &mut sink, 200)).unwrap_err();
        assert!(error.contains("ended early"), "{error}");
    }

    struct FailingSink;
    impl Sink for FailingSink {
        async fn write(&mut self, _data: &[u8]) -> Result<(), String> {
            Err("disk full".into())
        }
    }

    #[test]
    fn a_failing_sink_stops_the_transfer_and_reports_why() {
        let mut source = Bytes::new(5 * CHUNK, CHUNK);
        let error = block_on(pump(&mut source, &mut FailingSink, (5 * CHUNK) as u64)).unwrap_err();
        assert_eq!(error, "disk full");
        assert!(source.at < 5 * CHUNK, "the loop must not keep reading after a write failed");
    }

    /// A write that cannot finish until the source has been asked for the next piece: only a loop that reads ahead gets through.
    struct WaitsForNextRead {
        asked: Rc<Cell<usize>>,
        first_write_seen_asks: Option<usize>,
    }

    struct YieldOnce(bool);
    impl std::future::Future for YieldOnce {
        type Output = ();
        fn poll(mut self: std::pin::Pin<&mut Self>, cx: &mut std::task::Context<'_>) -> std::task::Poll<()> {
            if self.0 {
                return std::task::Poll::Ready(());
            }
            self.0 = true;
            cx.waker().wake_by_ref();
            std::task::Poll::Pending
        }
    }

    impl Sink for WaitsForNextRead {
        async fn write(&mut self, _data: &[u8]) -> Result<(), String> {
            if self.first_write_seen_asks.is_none() {
                for _ in 0..1000 {
                    if self.asked.get() >= 2 {
                        break;
                    }
                    YieldOnce(false).await;
                }
                self.first_write_seen_asks = Some(self.asked.get());
            }
            Ok(())
        }
    }

    #[test]
    fn the_next_piece_is_read_while_the_previous_one_is_being_written() {
        let mut source = Bytes::new(3 * CHUNK, CHUNK);
        let mut sink = WaitsForNextRead { asked: source.asked.clone(), first_write_seen_asks: None };
        block_on(pump(&mut source, &mut sink, (3 * CHUNK) as u64)).unwrap();
        assert!(sink.first_write_seen_asks.unwrap() >= 2, "the second read must have started before the first write finished");
    }
}
