# ADR 0003: Spring AI is the sole core abstraction / 核心只采用 Spring AI

- Status / 状态: Accepted / 已接受
- Date / 日期: 2026-07-19

## Decision / 决策

Spring AI is the only Java AI abstraction allowed in core modules. `RuntimeProvider` is Apvero's provider-neutral domain SPI; Spring AI adapters implement it without exposing provider SDK types.

Spring AI 是核心模块唯一 Java AI 抽象。`RuntimeProvider` 是 Apvero 的厂商无关领域 SPI；Spring AI 适配器实现该 SPI，但不能暴露厂商 SDK 类型。

LangChain4j, Spring AI Alibaba or direct vendor SDKs require a separate compatibility adapter and an approved ADR proving a capability that Spring AI cannot supply.

LangChain4j、Spring AI Alibaba 或厂商 SDK 只能进入独立兼容适配器，并且 ADR 必须证明 Spring AI 无法提供所需能力。

## Consequences / 影响

- Core contracts remain stable when providers change.
- Contributors learn one abstraction rather than several overlapping ones.
- Some vendor-specific features may wait for an adapter instead of leaking into the domain.
