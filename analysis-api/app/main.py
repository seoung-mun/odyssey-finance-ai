from fastapi import FastAPI

app = FastAPI(title="analysis-api")


@app.get("/health")
def health():
    return {"status": "ok"}
