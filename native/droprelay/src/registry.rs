use std::time::Duration;

use iroh_base::EndpointId;

use crate::access::DropRegistry;

const ANSWER_LIMIT: Duration = Duration::from_secs(5);

/// Whether the registry's answer lets the connection in: status 200 and the body exactly `true`.
pub fn answer_allows(status: u16, body: &str) -> bool {
    status == 200 && body.trim_end_matches(['\r', '\n']) == "true"
}

/// Asks the FileApex Worker whether an endpoint id is a registered guest drop id. Any failure counts as "not registered".
#[derive(Debug)]
pub struct WorkerRegistry {
    client: reqwest::Client,
    url: String,
    bearer_token: Option<String>,
}

impl WorkerRegistry {
    pub fn new(url: String, bearer_token: Option<String>) -> Result<Self, reqwest::Error> {
        let client = reqwest::Client::builder().timeout(ANSWER_LIMIT).build()?;
        Ok(Self { client, url, bearer_token })
    }
}

impl DropRegistry for WorkerRegistry {
    async fn is_registered(&self, id: EndpointId) -> bool {
        let mut request = self.client.post(&self.url).header("X-Iroh-Endpoint-Id", id.to_string());
        if let Some(token) = &self.bearer_token {
            request = request.bearer_auth(token);
        }
        let Ok(response) = request.send().await else {
            return false;
        };
        let status = response.status().as_u16();
        match response.text().await {
            Ok(body) => answer_allows(status, &body),
            Err(_) => false,
        }
    }
}
