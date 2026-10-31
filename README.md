# HealOps

HealOps is a containerized DevOps assistant that combines a Gemini-powered Spring Boot agent with specialized API services for Docker, Prometheus, Kubernetes, OpenStack, and distributed tracing. It provides a simple Angular chat interface for inspecting infrastructure, reviewing health information, and running supported operational actions.

## Main features

- List, inspect, start, stop, and troubleshoot Docker containers.
- Collect container metrics through Prometheus and cAdvisor.
- List and analyze Kubernetes pods when the Kubernetes profile is enabled.
- Ingest and analyze OpenStack service logs and metrics.
- Query tracing information from a Jaeger-compatible data source.
- Send monitoring alerts by email when email credentials are configured.

## Architecture

The Angular UI sends questions to the Spring Boot agent. The agent routes supported requests to the appropriate Python/FastAPI service, then uses Gemini to format or analyze the returned data.

| Component | Purpose | Host port |
| --- | --- | ---: |
| `agentuiservice` | Angular UI served by Nginx | 80 |
| `agentservice` | Spring Boot AI agent | 8888 |
| `docker-mcp` | Docker management API | 8000 |
| `monitoring-mcp` | Prometheus metrics and alerting API | 9001 |
| `k8s-mcp` | Kubernetes API integration (optional profile) | 7777 |
| `openstack-mcp` | OpenStack logs, metrics, and actions | 7779 |
| `tracing-mcp` | Tracing and APM API | 9005 |
| `prometheus` | Metrics database and query UI | 9090 |
| `cadvisor` | Container metrics exporter | 8080 |

## Prerequisites

- Docker Desktop with Docker Compose.
- A Gemini API key from Google AI Studio.
- Kubernetes enabled in Docker Desktop if Kubernetes support is required.
- A Gmail app password if email alerts are required.

## Quick start

1. Create your local environment file:

   ```powershell
   Copy-Item .env.example .env
   ```

2. Edit `.env` and provide the values you need:

   ```dotenv
   GEMINI_API_KEY=your_gemini_api_key
   EMAIL_USER=sender@gmail.com
   EMAIL_APP_PASSWORD=your_google_app_password
   ALERT_EMAIL_TO=recipient@example.com
   PROMETHEUS_BASE_URL=http://prometheus:9090
   ```

   `GEMINI_API_KEY` is required for the chat agent. The email values are only needed for email alerts.

3. Build and start the default stack:

   ```powershell
   docker compose up -d --build
   ```

4. Open the application at [http://localhost](http://localhost).

Check the running services with:

```powershell
docker compose ps -a
```

## Kubernetes profile

The Kubernetes service is optional. The current Compose configuration expects a container-specific kubeconfig at `%USERPROFILE%\.kube\config-healops`.

On Windows with Docker Desktop, create it from the active context:

```powershell
$sourceConfig = "$env:USERPROFILE\.kube\config"
$healOpsConfig = "$env:USERPROFILE\.kube\config-healops"
Copy-Item -LiteralPath $sourceConfig -Destination $healOpsConfig -Force

$clusterName = kubectl config view --minify -o jsonpath='{.contexts[0].context.cluster}'
$hostServer = kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}'
$containerServer = $hostServer -replace '127\.0\.0\.1', 'host.docker.internal'

kubectl --kubeconfig="$healOpsConfig" config set-cluster $clusterName `
  --server="$containerServer" `
  --tls-server-name=localhost
```

Start Kubernetes support:

```powershell
docker compose --profile kubernetes up -d --build
```

Verify it with:

```powershell
Invoke-RestMethod http://localhost:7777/k8s/pods
```

## Useful URLs

- Application UI: [http://localhost](http://localhost)
- Prometheus: [http://localhost:9090](http://localhost:9090)
- Docker API docs: [http://localhost:8000/docs](http://localhost:8000/docs)
- Monitoring API docs: [http://localhost:9001/docs](http://localhost:9001/docs)
- Kubernetes API docs: [http://localhost:7777/docs](http://localhost:7777/docs)
- OpenStack API docs: [http://localhost:7779/docs](http://localhost:7779/docs)
- Tracing API docs: [http://localhost:9005/docs](http://localhost:9005/docs)

Example chat requests:

```text
List all Docker containers
List all running pods
Show the logs for container <container-id>
Analyze container <container-id>
```

## Logs and shutdown

Follow all service logs:

```powershell
docker compose logs -f
```

Follow only the agent logs:

```powershell
docker compose logs -f --tail=100 agentservice
```

Stop the project:

```powershell
docker compose --profile kubernetes down
```


