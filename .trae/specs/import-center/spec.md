# SmartNote 导入中心 - 产品需求文档

## Overview
- **Summary**: 实现一个导入中心功能，允许用户将外部文档（Markdown文件、Markdown ZIP包、Word文档）批量导入到SmartNote系统中。
- **Purpose**: 提供一个迁移入口能力，方便用户将现有的文档资源快速迁移到SmartNote平台。
- **Target Users**: SmartNote系统的注册用户。

## Goals
- 支持单个Markdown文件导入
- 支持Markdown ZIP包批量导入
- 支持单个Word文档导入
- 提供导入进度状态显示
- 允许用户选择导入目标知识库
- 处理导入过程中的图片资源
- 提供导入结果摘要

## Non-Goals (Out of Scope)
- 直接调用语雀开放平台API
- 支持Notion、Confluence等第三方平台导入

## Background & Context
- SmartNote是一个知识库管理系统，用户需要一个高效的方式将现有文档迁移到系统中。
- 当前系统没有批量导入功能，用户只能手动创建笔记并复制内容。

## Functional Requirements
- **FR-1**: 提供导入入口，在知识库首页和知识库详情页
- **FR-2**: 支持单个Markdown文件导入，自动创建笔记
- **FR-3**: 支持Markdown ZIP包批量导入，按目录结构创建文件夹与笔记
- **FR-4**: 支持单个Word文档导入，转换为Markdown后创建笔记
- **FR-5**: 处理导入过程中的图片资源，上传至本地存储并更新引用路径
- **FR-6**: 显示导入进度状态
- **FR-7**: 提供导入结果摘要，包括成功数量、失败数量、失败原因
- **FR-8**: 允许用户选择导入目标知识库

## Non-Functional Requirements
- **NFR-1**: 单次导入文件总大小上限为100MB
- **NFR-2**: 单个ZIP包最大文件数为1000
- **NFR-3**: 100篇以内的批量导入应在合理时间内完成
- **NFR-4**: 导入失败不得影响已成功导入的文档

## Constraints
- **Technical**: 需补充导入任务记录表和相关状态字段
- **Business**: 导入功能应与现有系统无缝集成

## Assumptions
- 用户已登录SmartNote系统
- 系统具备足够的存储空间处理导入文件
- 系统具备Word转Markdown的能力

## Acceptance Criteria

### AC-1: 单个Markdown文件导入
- **Given**: 用户在导入中心选择单个Markdown文件并选择目标知识库
- **When**: 用户点击导入按钮
- **Then**: 系统应创建一篇对应内容的笔记，图片资源正确处理
- **Verification**: `programmatic`
- **Notes**: 文件名应作为笔记标题，为空时使用"未命名文档-时间戳"

### AC-2: Markdown ZIP包批量导入
- **Given**: 用户在导入中心选择Markdown ZIP包并选择目标知识库
- **When**: 用户点击导入按钮
- **Then**: 系统应按ZIP目录结构创建文件夹与笔记，图片资源正确处理
- **Verification**: `programmatic`
- **Notes**: 支持的ZIP包最大文件数为1000

### AC-3: Word文档导入
- **Given**: 用户在导入中心选择单个Word文档并选择目标知识库
- **When**: 用户点击导入按钮
- **Then**: 系统应将Word正文转换为Markdown后创建笔记，图片资源正确处理
- **Verification**: `programmatic`

### AC-4: 导入进度显示
- **Given**: 用户开始导入操作
- **When**: 导入过程中
- **Then**: 系统应显示导入进度状态
- **Verification**: `human-judgment`

### AC-5: 导入结果摘要
- **Given**: 导入操作完成
- **When**: 系统处理完所有文件
- **Then**: 系统应输出结果摘要，包括成功数量、失败数量、失败原因
- **Verification**: `human-judgment`

### AC-6: 图片资源处理
- **Given**: 导入的文档包含图片
- **When**: 系统处理导入文件
- **Then**: 系统应将图片上传至本地文件存储，并替换文档中的引用路径
- **Verification**: `programmatic`

### AC-7: 导入失败处理
- **Given**: 导入过程中部分文件失败
- **When**: 系统完成导入操作
- **Then**: 已成功导入的文档应正常显示，失败文件应显示具体原因
- **Verification**: `programmatic`

### AC-8: 重名处理
- **Given**: 导入的文档与目标位置已存在同名笔记
- **When**: 系统处理导入文件
- **Then**: 系统应对重名笔记自动追加序号
- **Verification**: `programmatic`

## Open Questions
- [ ] 系统是否已具备Word转Markdown的能力？
- [ ] 导入任务记录的存储方式是什么？
- [ ] 导入过程中的错误处理机制是否需要进一步细化？