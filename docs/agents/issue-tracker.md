# Issue tracker

本项目使用 Local Markdown。规格和票据保存在 `.scratch/` 下。

- 每个功能使用一个目录 `.scratch/<feature-slug>/`。规格写入该目录的 `spec.md`。
- 实施票据写入 `issues/<NN>-<slug>.md`，从 `01` 起编号，每张票据单独存档。
- 在票据顶部附近用 `Status:` 记录分诊状态。取值见 [Triage labels](triage-labels.md)。
- 在文件末尾的 `## Comments` 下追加评论和讨论记录。

发布时按上述路径创建 Markdown 文件及所需目录。读取时打开引用的文件。若引用只给出编号，在当前功能的 `issues/` 中查找。

## Wayfinding

- 将 Notes、Decisions-so-far 和 Fog 记录在 `.scratch/<effort>/map.md`。
- 子票据保存在 `.scratch/<effort>/issues/<NN>-<slug>.md`。在正文记录问题，用 `Type:` 标记 `research`、`prototype`、`grilling` 或 `task`。
- 用 `Blocked by: NN, NN` 记录前置票据。所有前置票据的状态为 `resolved` 后，子票据才可领取。
- 按编号领取第一个开放、无阻塞且未领取的子票据。开始工作前先保存 `Status: claimed`。
- 完成后在 `## Answer` 下追加答案，将状态设为 `Status: resolved`，并在 `map.md` 的 Decisions-so-far 中追加结论摘要和票据链接。
