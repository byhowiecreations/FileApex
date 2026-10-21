package main

import (
	"sync"
	"time"
)

// limiter is a token bucket per key (the Firebase user id), with a hard cap on tracked keys.
type limiter struct {
	rate  float64 // tokens per second
	burst float64
	now   func() time.Time

	mu      sync.Mutex
	buckets map[string]*bucket
}

type bucket struct {
	tokens float64
	last   time.Time
}

const maxTrackedKeys = 100000

func newLimiter(perMinute int, burst int, now func() time.Time) *limiter {
	return &limiter{rate: float64(perMinute) / 60, burst: float64(burst), now: now, buckets: map[string]*bucket{}}
}

// allow takes one token; when none is left it returns how long to wait.
func (l *limiter) allow(key string) (bool, time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b, ok := l.buckets[key]
	if !ok {
		if len(l.buckets) >= maxTrackedKeys {
			l.evict(now)
		}
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[key] = b
	}
	b.tokens += now.Sub(b.last).Seconds() * l.rate
	if b.tokens > l.burst {
		b.tokens = l.burst
	}
	b.last = now
	if b.tokens >= 1 {
		b.tokens--
		return true, 0
	}
	return false, time.Duration((1 - b.tokens) / l.rate * float64(time.Second))
}

// evict drops every bucket that has refilled completely; those keys would start full anyway.
func (l *limiter) evict(now time.Time) {
	for key, b := range l.buckets {
		if b.tokens+now.Sub(b.last).Seconds()*l.rate >= l.burst {
			delete(l.buckets, key)
		}
	}
}
