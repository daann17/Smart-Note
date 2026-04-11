# SmartNote 导入中心 - 实现计划

## [x] Task 1: 后端数据模型设计与实现
- **Priority**: P0
- **Depends On**: None
- **Description**: 
  - 创建导入任务记录表(ImportTask)
  - 定义导入任务状态字段：`PENDING / PROCESSING / SUCCESS / PARTIAL_SUCCESS / FAILED`
  - 创建导入日志明细表或结构化失败列表
- **Acceptance Criteria Addressed**: AC-7
- **Test Requirements**:
  - `programmatic` TR-1.1: 导入任务记录能正确存储和更新状态
  - `programmatic` TR-1.2: 导入失败时能记录失败原因
- **Notes**: 需要考虑与现有用户和知识库模型的关联

## [x] Task 2: 后端导入服务实现
- **Priority**: P0
- **Depends On**: Task 1
- **Description**: 
  - 实现文件上传处理逻辑
  - 实现Markdown文件解析与导入
  - 实现ZIP包解压与目录结构处理
  - 实现Word文档转Markdown功能
  - 实现图片资源处理与路径更新
- **Acceptance Criteria Addressed**: AC-1, AC-2, AC-3, AC-6
- **Test Requirements**:
  - `programmatic` TR-2.1: 单个Markdown文件能正确导入并创建笔记
  - `programmatic` TR-2.2: Markdown ZIP包能按目录结构导入
  - `programmatic` TR-2.3: Word文档能正确转换为Markdown并导入
  - `programmatic` TR-2.4: 导入的图片资源能正确存储并更新引用路径
- **Notes**: 需要处理文件大小限制和ZIP包文件数量限制

## [x] Task 3: 后端API接口实现
- **Priority**: P0
- **Depends On**: Task 2
- **Description**: 
  - 创建导入相关的REST API接口
  - 实现导入任务状态查询接口
  - 实现导入结果获取接口
- **Acceptance Criteria Addressed**: AC-4, AC-5
- **Test Requirements**:
  - `programmatic` TR-3.1: API接口能正确接收并处理导入请求
  - `programmatic` TR-3.2: 能查询导入任务的实时状态
  - `programmatic` TR-3.3: 能获取导入结果摘要
- **Notes**: 需要考虑API的安全性和权限控制

## [x] Task 4: 前端导入中心UI组件实现
- **Priority**: P0
- **Depends On**: Task 3
- **Description**: 
  - 创建导入中心模态框或页面
  - 实现文件选择和上传界面
  - 实现目标知识库选择功能
  - 实现导入进度显示
  - 实现导入结果展示
- **Acceptance Criteria Addressed**: AC-4, AC-5
- **Test Requirements**:
  - `human-judgment` TR-4.1: UI界面美观且易用
  - `programmatic` TR-4.2: 能正确调用后端API并显示结果
- **Notes**: 需要考虑文件上传的用户体验和错误处理

## [x] Task 5: 前端导入入口实现
- **Priority**: P1
- **Depends On**: Task 4
- **Description**: 
  - 在知识库首页添加"导入"入口
  - 在知识库详情页添加"导入到当前知识库"入口
- **Acceptance Criteria Addressed**: FR-1
- **Test Requirements**:
  - `human-judgment` TR-5.1: 导入入口位置合理且易于发现
  - `programmatic` TR-5.2: 点击入口能正确打开导入中心
- **Notes**: 需要确保入口在不同页面的一致性

## [x] Task 6: 导入业务规则实现
- **Priority**: P1
- **Depends On**: Task 2
- **Description**: 
  - 实现文件大小限制检查
  - 实现ZIP包文件数量限制检查
  - 实现重名处理逻辑
  - 实现导入失败的部分成功处理
- **Acceptance Criteria Addressed**: AC-7, AC-8
- **Test Requirements**:
  - `programmatic` TR-6.1: 超过大小限制的文件能被正确拒绝
  - `programmatic` TR-6.2: 超过文件数量限制的ZIP包能被正确拒绝
  - `programmatic` TR-6.3: 重名笔记能自动追加序号
  - `programmatic` TR-6.4: 部分失败时已成功导入的文档能正常显示
- **Notes**: 需要考虑边界情况和错误处理

## [x] Task 7: 系统集成与测试
- **Priority**: P2
- **Depends On**: All previous tasks
- **Description**: 
  - 集成前端和后端功能
  - 进行端到端测试
  - 进行性能测试，确保100篇以内的批量导入能在合理时间内完成
- **Acceptance Criteria Addressed**: All AC
- **Test Requirements**:
  - `programmatic` TR-7.1: 所有功能能正常集成并工作
  - `programmatic` TR-7.2: 批量导入性能符合要求
- **Notes**: 需要模拟各种导入场景进行测试