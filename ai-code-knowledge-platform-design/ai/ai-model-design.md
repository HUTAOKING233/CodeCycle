# AI Model Design

## 模型解耦
Chat Model 与 Embedding Model 完全独立。

## 冻结版本（v3.6）

- Spring AI 2.0.1（唯一 AI 主框架）；
- 默认本地模型运行时：Ollama 0.12.1（稳定镜像 tag 在容器化接入时确认并回写冻结）；
- 默认 Embedding 模型：Qwen3-Embedding-0.6B（Ollama 本地运行，维度默认 1024）；
- Chat Model 由用户自配（Provider / Endpoint / Key / Model），不做模型供应商平台。

## Chat Model
用户配置：
- Provider/Protocol
- Endpoint
- API Key
- Model Name
- 必要参数

第一版不提供完整模型供应商平台。

## Embedding
优先轻量开源模型，例如 Qwen3-Embedding-0.6B；支持本地模型服务或兼容 API。

## Review
允许：
```text
Diff → Context Builder → LLM → 是否需要更多上下文 → Analysis/LLM → Finding
```

但必须限制深度、Token、调用次数和时间。

## Structured Output
优先要求 LLM 输出结构化结果，再转换为领域对象。不能直接将模型结果标记为 Knowledge Published。
