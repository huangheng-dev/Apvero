# P2.1 Parser 候选依赖决策

状态：批准用于基准验证；生产解析端点继续禁用
日期：2026-07-22
范围：仅 P2.1a

## 决策

Apvero 在 Python 3.14 Worker 上明确评测以下候选：

- `pypdf 6.14.2`：从含文本的 PDF 中进行有边界的文本提取；
- `python-docx 1.2.0`：访问 DOCX 结构；
- `beautifulsoup4 4.15.0`：使用明确指定的标准库 `html.parser` 后端处理已捕获 HTML。

它们在 P2.1a 中只是开发环境基准依赖。只有 P2.1e 的对抗语料和进程限制全部通过后，才能把它们移入生产依赖并启用 `/internal/v1/documents/process`。

`pypdf` 是纯 Python，声明支持 Python 3.14，使用 BSD-3-Clause，并支持逐页文本提取；它不提供 OCR，也不能保证完美还原 PDF 版面。`python-docx` 使用 MIT，可访问段落和标题，但 ZIP/XML 输入仍必须经过 Apvero 自己的预检限制。Beautiful Soup 使用 MIT；明确指定 `html.parser` 可避免因环境中安装了不同 Parser 而改变输出。提取正文前会移除活动元素。

不采用 PyMuPDF，因为其 AGPL/商业双重许可不适合当前 Apache-2.0 发行基线。只有代表性语料证明存在明确质量差距时，才继续比较 `pdfminer.six`、`html5lib` 或其他 DOCX Reader。

主要依据：

- https://pypi.org/project/pypdf/
- https://pypdf.readthedocs.io/en/latest/user/extract-text.html
- https://pypi.org/project/python-docx/
- https://github.com/python-openxml/python-docx/blob/master/LICENSE
- https://www.crummy.com/software/BeautifulSoup/bs4/doc/
- https://github.com/pymupdf/PyMuPDF

## 基准与语料契约

在 `apps/ai-worker` 目录执行：

```bash
uv run python -m benchmarks.benchmark_parser_candidates --iterations 25
```

已提交的烟雾语料覆盖 UTF-8/Code Point、Markdown 标题、包含活动内容的已捕获 HTML、一个生成式单页 PDF 和生成式 DOCX 标题。基准要求多次执行的输出摘要完全一致，并报告输入字节、输出 Code Point、中位耗时和最大耗时。

这套小型生成语料只能发现依赖和运行时回归，**不能**证明生产文件大小、页数、压缩展开量、内存、CPU 或超时上限。启用端点前，Manifest 中列出的对抗类型必须具备真实 Fixture 和容器实测。因此当前：

- `APVERO_KNOWLEDGE_ENABLED=false` 继续作为默认值；
- Worker 没有公网路由或宿主机端口；
- 不宣称支持 PDF OCR、认证抓取、XLSX、PPTX 或图片；
- 不得把任何 Parser 上限宣传成已通过生产实测。

2026-07-22 的首次 Windows/Python 3.14 烟雾验证为每个 Case 执行 25 次确定性检查。中位耗时约为：Text 0.005 ms、Markdown 0.004 ms、HTML 0.672 ms、生成式单页 PDF 0.741 ms、生成式 DOCX 13.144 ms。这些数字只证明基准工具能够运行，不能用于容量规划。

## P2.1e 安全与运维门禁

1. Java 在调用 Worker 前完成授权、不可变快照身份、媒体结构和输入边界验证。
2. DOCX ZIP/XML 预检在进入库遍历前拒绝宏、加密、畸形归档和过度展开。
3. PDF 处理拒绝加密，并在隔离 Worker 中限制字节、页数、时间和输出。
4. Worker 保持非 Root、只读、资源受限、无凭证、无数据库访问、无外部网络。
5. Java 在持久化前验证每个响应摘要、Ordinal、Code Point Offset、Anchor 和输出边界。
6. CI 必须通过依赖、许可、漏洞、畸形输入、超时和重复性检查。

## 回滚

P2.1a 可以移除仅用于开发的候选依赖和基准文件，不需要数据迁移，因为还没有 Parser 端点、持久化 Document 或 Release 依赖它们。默认关闭配置和 Worker 私有网络可独立保留安全状态。
