FROM python:3.13.2-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

RUN useradd --create-home --uid 10001 demo
USER demo

EXPOSE 8095

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8095"]
