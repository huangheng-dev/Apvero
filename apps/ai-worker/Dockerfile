FROM python:3.14-slim
LABEL org.opencontainers.image.title="Apvero AI Worker"
LABEL org.opencontainers.image.source="https://github.com/huangheng-dev/Apvero"
LABEL org.opencontainers.image.licenses="Apache-2.0"
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN useradd --system --uid 10002 --create-home apvero
WORKDIR /app
COPY apps/ai-worker/pyproject.toml ./
COPY apps/ai-worker/src ./src
RUN pip install --no-cache-dir .
USER 10002
EXPOSE 8090
CMD ["uvicorn", "apvero_worker.main:app", "--host", "0.0.0.0", "--port", "8090"]
