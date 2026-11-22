from datetime import datetime, timezone

try:
    import docker
except ImportError:
    docker = None

class DockerManager:
    def __init__(self):
        if docker is None:
            raise RuntimeError("docker SDK is not installed")
        self.client = docker.from_env()

    def list_containers(self, all=False):
        containers = self.client.containers.list(all=all)
        result = []
        for c in containers:
            attrs = getattr(c, "attrs", None) or {}
            state = attrs.get("State") or {}
            health = (state.get("Health") or {}).get("Status")
            config_image = (attrs.get("Config") or {}).get("Image")
            if config_image:
                image_tags = [config_image]
            else:
                image_obj = getattr(c, "image", None)
                image_tags = getattr(image_obj, "tags", []) if image_obj is not None else []
            entry = {
                "id": c.short_id,
                "name": c.name,
                "status": c.status,
                "image": image_tags,
            }
            if health is not None:
                entry["health"] = health
            if state.get("StartedAt"):
                entry["started_at"] = state.get("StartedAt")
            result.append(entry)
        return result

    def get_container_stats(self, container_id: str):
        container = self.client.containers.get(container_id)
        stats = container.stats(stream=False) or {}
        cpu_stats = stats.get("cpu_stats") or {}
        cpu_usage = cpu_stats.get("cpu_usage") or {}
        memory_stats = stats.get("memory_stats") or {}
        networks = stats.get("networks")
        rx_bytes = 0
        tx_bytes = 0
        if isinstance(networks, dict) and networks:
            iface = networks.get("eth0")
            if not isinstance(iface, dict):
                iface = next((v for v in networks.values() if isinstance(v, dict)), {})
            rx_bytes = iface.get("rx_bytes", 0) or 0
            tx_bytes = iface.get("tx_bytes", 0) or 0
        memory_usage = memory_stats.get("usage", 0) or 0
        memory_limit = memory_stats.get("limit", 0) or 0
        result = {
            "cpu_total": cpu_usage.get("total_usage", 0) or 0,
            "memory_usage": memory_usage,
            "memory_limit": memory_limit,
            "network_rx": rx_bytes,
            "network_tx": tx_bytes,
        }
        if memory_limit > 0:
            result["memory_percent"] = round((memory_usage / memory_limit) * 100.0, 2)
        return result

    def start_container(self, container_id: str):
        c = self.client.containers.get(container_id)
        c.start()
        return f"Container {c.name} started."

    def stop_container(self, container_id: str):
        c = self.client.containers.get(container_id)
        c.stop()
        return f"Container {c.name} stopped."

    def restart_container(self, container_id: str):
        c = self.client.containers.get(container_id)
        c.restart()
        return f"Container {c.name} restarted."
    
    def create_container(self, image: str, name: str = None, command: str = None, ports: dict = None):
        """
        Create and start a new Docker container.
        """
        try:
            container = self.client.containers.run(
                image=image,
                name=name,
                command=command,
                ports=ports,
                detach=True  # run in background
            )
            return {
                "message": f"Container '{container.name}' created and started successfully.",
                "id": container.short_id,
                "image": image,
                "status": container.status
            }
        except docker.errors.ImageNotFound:
            # Optional: auto-pull if not found
            self.client.images.pull(image)
            container = self.client.containers.run(
                image=image,
                name=name,
                command=command,
                ports=ports,
                detach=True
            )
            return {
                "message": f"Image '{image}' pulled and container '{container.name}' started.",
                "id": container.short_id,
                "status": container.status
            }
        except docker.errors.APIError as e:
            raise Exception(f"Docker API error: {e.explanation}")
    
    
    def get_container_logs(self, container_id: str, tail: int = 100, since_started: bool = False):
        container = self.client.containers.get(container_id)
        since_dt = None
        if since_started:
            started_raw = ((getattr(container, "attrs", None) or {}).get("State") or {}).get("StartedAt")
            if isinstance(started_raw, str) and started_raw and not started_raw.startswith("0001-01-01"):
                try:
                    normalized = started_raw.rstrip("Z")
                    if "." in normalized:
                        head, frac = normalized.split(".", 1)
                        normalized = f"{head}.{frac[:6]}"
                    since_dt = datetime.fromisoformat(normalized).replace(tzinfo=timezone.utc)
                except ValueError:
                    since_dt = None
        kwargs = {"tail": tail}
        if since_dt is not None:
            kwargs["since"] = since_dt
        logs = container.logs(**kwargs).decode("utf-8", errors="replace")
        return logs
    
    def exec_in_container(self, container_id: str, command: str):
        """
        Execute a shell command inside a running container.
         Returns the command output.
        """
        container = self.client.containers.get(container_id)

        if container.status != "running":
           raise Exception(f"Container {container_id} is not running.")

        exec_result = container.exec_run(command)
        raw_output = exec_result.output
        output = raw_output.decode("utf-8", errors="replace") if isinstance(raw_output, (bytes, bytearray)) else str(raw_output)
        return {"command": command, "output": output}


    
