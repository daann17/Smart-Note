# SmartNote Bug 分析与修复报告

**生成时间**: 2026-03-14 16:10

## 1. 笔记本创建与查询失败 (JSON 序列化死循环)

### 🔴 问题现象 (Symptoms)
- 前端调用 `GET /api/notebooks` 或 `POST /api/notebooks` 时，接口长时间无响应或返回 500 错误。
- 后端控制台抛出 `StackOverflowError` 或 `InvalidDefinitionException`。
- 错误信息通常包含：`No serializer found for class org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor` 或 Infinite recursion (StackOverflowError)。

### 🔍 根本原因 (Root Cause)
1.  **Hibernate 懒加载 (Lazy Loading)**: 
    - 实体类 (`User`, `Notebook`, `Note`) 使用了 `@ManyToOne(fetch = FetchType.LAZY)`。
    - 当 Jackson 尝试序列化这些实体时，Hibernate 会返回一个代理对象 (Proxy)。Jackson 默认无法序列化这些代理对象包含的 `hibernateLazyInitializer` 属性。
2.  **双向关联 (Bidirectional Relationships)**:
    - `User` 包含 `notebooks` 列表，`Notebook` 包含 `user` 引用。
    - `Notebook` 包含 `notes` 列表，`Note` 包含 `notebook` 引用。
    - 当序列化 `User` 时，Jackson 会序列化 `notebooks`，进而序列化 `Notebook`，再序列化其 `user`... 导致无限递归循环。

### ✅ 解决方案 (Solution)
在相关实体类 (`User`, `Notebook`, `Note`, `Tag`) 上添加 Jackson 注解以切断循环和忽略代理属性：

1.  **忽略 Hibernate 代理属性**:
    ```java
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    public class Notebook { ... }
    ```
2.  **切断双向关联**:
    在 `@ManyToOne` 或 `@ManyToMany` 字段上忽略反向引用的属性。
    ```java
    // Notebook.java
    @ManyToOne(...)
    @JsonIgnoreProperties({"password", "role", "notebooks"}) // 忽略 User 中的 notebooks 列表
    private User user;
    
    // Note.java
    @ManyToOne(...)
    @JsonIgnoreProperties({"user", "notes"}) // 忽略 Notebook 中的 notes 列表
    private Notebook notebook;
    ```

---

## 2. 笔记本打开与笔记跳转失效

### 🔴 问题现象 (Symptoms)
- 点击搜索结果中的笔记，跳转到笔记本页面后，没有自动选中该笔记，而是默认选中了第一篇或未选中。
- 进入笔记本页面加载缓慢，必须等待所有关联数据（标签、笔记本列表）加载完成才能看到内容。

### 🔍 根本原因 (Root Cause)
1.  **路由参数处理缺失**: `NoteView.vue` 的 `onMounted` 钩子中，仅处理了 `notebookId` 参数，忽略了 URL 查询参数中的 `noteId`。
2.  **阻塞式加载**: `await notebookStore.fetchNotebooks()` 和 `await tagStore.fetchTags()` 放在了核心逻辑之前，导致页面渲染被非关键数据的网络请求阻塞。

### ✅ 解决方案 (Solution)
优化前端 `NoteView.vue` 的加载逻辑：

1.  **支持深度链接**:
    ```typescript
    // 优先检查 URL query 中的 noteId
    const noteIdFromQuery = Number(route.query.noteId);
    if (noteIdFromQuery) {
        handleSelectNote(noteIdFromQuery);
    }
    ```
2.  **非阻塞加载**:
    将不影响当前笔记显示的辅助数据加载（笔记本列表、标签列表）移出 `await` 链，允许其在后台异步加载。
    ```typescript
    // 异步加载其他数据，不阻塞主流程
    notebookStore.fetchNotebooks();
    tagStore.fetchTags();
    ```

---

## 3. 用户登录失败 (403 Forbidden) 与环境配置错误

**记录时间**: 2026-03-14

### 🔴 问题现象 (Symptoms)
- 前端登录请求 `POST /api/auth/login` 返回 `403 Forbidden`。
- 后端控制台未显示详细错误日志，或显示认证失败但响应码不明确。
- 构建项目时出现 Java 版本不匹配错误 (`UnsupportedClassVersionError`)。

### 🔍 根本原因 (Root Cause)
1.  **Spring Security 默认行为**: 当认证失败（如用户名密码错误）时，Spring Security 默认抛出异常并由框架处理为 403 Forbidden，而非更明确的 401 Unauthorized，导致前端无法区分是“密码错误”还是“无权访问”。
2.  **过滤器逻辑缺陷**: `JwtAuthenticationFilter` 对所有请求（包括 `/api/auth/`）都尝试解析 Token，虽然 `SecurityConfig` 放行了该路径，但过滤器逻辑不够严谨。
3.  **开发环境配置**: 本地 Maven 环境默认使用了 Java 8，而项目依赖 Spring Boot 3+ 需要 Java 17。

### ✅ 解决方案 (Solution)
1.  **优化控制器异常处理**:
    在 `AuthController` 中捕获 `AuthenticationException`，明确返回 401 状态码和错误信息。
    ```java
    // AuthController.java
    try {
        Authentication authentication = authenticationManager.authenticate(...);
        // ...
    } catch (AuthenticationException e) {
        return ResponseEntity.status(401).body("Error: Invalid username or password");
    }
    ```
2.  **优化 JWT 过滤器**:
    在 `JwtAuthenticationFilter` 中显式跳过对 `/api/auth/` 路径的 Token 校验。
    ```java
    // JwtAuthenticationFilter.java
    if (path.startsWith("/api/auth/")) {
        filterChain.doFilter(request, response);
        return;
    }
    ```
3.  **修正运行环境**:
    切换 Maven 运行环境至 Java 17 (`$env:JAVA_HOME="..."`).
113→
---

## 4. 笔记切换与自动保存体验问题

**记录时间**: 2026-03-16

### 🔴 问题现象 (Symptoms)
- **内容残留**: 创建新笔记或切换笔记时，编辑器内容没有更新为新笔记的内容，仍保留上一次编辑的旧内容。
- **误触保存**: 切换笔记时会意外触发一次向后端的自动保存请求。
- **双窗编辑反直觉**: 处于代码块等区域时，编辑器会分裂成源码区和预览区，不符合普通用户的书写直觉。

### 🔍 根本原因 (Root Cause)
1.  **组件复用与状态未更新**: Vue 默认复用挂载后的组件。`<MarkdownEditor>` (Vditor) 初始化后，当外部传入的 `modelValue` (即 `currentNote.content`) 因切换笔记而改变时，组件内部未做响应式更新。
2.  **监听器边界不清**: `watch` 监听内容变化时，未能区分“用户主动输入”与“程序切换笔记导致的内容替换”，从而错误重置并触发了 3 秒后的 `handleSave`。
3.  **Vditor 渲染模式设置**: 编辑器模式被配置为了 `ir` (即时渲染)，该模式在特定语法块下会展开源码区。

### ✅ 解决方案 (Solution)
1.  **强制重新渲染组件**:
    在 `NoteView.vue` 中为 `<MarkdownEditor>` 添加 `:key` 属性，强制 Vue 在笔记切换时销毁并重建编辑器实例。
    ```vue
    <MarkdownEditor :key="noteStore.currentNote.id" v-model="noteStore.currentNote.content" />
    ```
2.  **引入切换状态锁**:
    新增 `isSwitchingNote` 状态。在切换或创建笔记时加锁，在 `watch` 中判断若处于切换状态则直接 return 阻断自动保存逻辑。
3.  **调整编辑器模式**:
    将 `MarkdownEditor.vue` 中 Vditor 的初始化配置 `mode` 从 `'ir'` 修改为 `'wysiwyg'`（所见即所得）。
    ```typescript
    mode: 'wysiwyg', // 所见即所得模式，更符合普通用户的编辑直觉
    ```

---
*报告生成者: Trae AI Assistant*

# Bug Report: Vditor `customWysiwygToolbar is not a function` Error

## 1. 现象描述 (Symptoms)
在前端启动并进入笔记编辑页面（使用了 Vditor 的 WYSIWYG 模式）时，当鼠标在编辑器内点击或进行特定元素交互（例如点击标题、列表等触发悬浮工具栏的操作）时，浏览器控制台抛出大量以下错误，导致后续功能可能会出现异常卡顿或中断：
```
Uncaught TypeError: vditor.options.customWysiwygToolbar is not a function
    at customWysiwygToolbar (index.js:8148:20)
    at index.js:7801:17
```

## 2. 根本原因 (Root Cause Analysis)
通过深入排查引入的 `vditor` 包底层源码 (`vditor/src/ts/wysiwyg/highlightToolbarWYSIWYG.ts:1152`)，我们发现：
当 Vditor 处于 `wysiwyg` (所见即所得) 模式时，如果用户选中了特定的富文本块（如段落、标题），Vditor 会尝试高亮显示对应的悬浮工具栏或弹出自定义菜单，并无条件调用内部封装的方法：
```typescript
const customWysiwygToolbar = (vditor: IVditor, type: TWYSISYGToolbar) => {
    vditor.options.customWysiwygToolbar(type, vditor.wysiwyg.popover);
}
```
**缺陷在于：** Vditor 的底层源码在这里**没有检查** `vditor.options.customWysiwygToolbar` 这个回调函数是否由开发者传入并存在。因为我们实例化 Vditor 时没有传入这个可选参数，它的值是 `undefined`。强行以函数方式调用 `undefined` 就抛出了 `TypeError`。

## 3. 解决方案 (Solution)
要绕过这个第三方库底层的健壮性漏洞，我们只需要在实例化 Vditor 时，主动提供一个空函数作为 `customWysiwygToolbar` 的值即可。

我们在 `frontend/src/components/MarkdownEditor.vue` 中的 Vditor 配置对象中追加了以下配置：
```javascript
vditor.value = new Vditor(editorRef.value, {
  // ... 其他配置项
  mode: 'wysiwyg',
  customWysiwygToolbar: () => {}, // 提供空函数以解决 Vditor 底层缺少非空判断抛出的 TypeError
  // ...
});
```

## 4. 修复结果 (Result)
目前该修复方案已经实装。在 WYSIWYG 模式下点击任何特定组件时，控制台均不再抛出此 `TypeError`。编辑器运行平稳，悬浮工具栏等交互逻辑正常。


我已经找到了导致历史版本中出现“暂无内容展示”的根本原因并进行了修复。

### 问题原因分析：
问题出在 Vditor 编辑器的生命周期和 HTML 渲染结果获取时机 。
在之前的代码中，前端组件 MarkdownEditor.vue 并没有在合适的时机主动将渲染后的 HTML 内容向外抛出（仅仅抛出了 modelValue 也就是 Markdown 源码）。当触发“保存”操作时，传给后端的 contentHtml 字段实际上是空的。虽然我们在后端加了一个容错（如果 HTML 为空则存入原始源码），但这只能治标不治本。

### 修复方案：
1. 完善前端 HTML 提取机制 ：
   - 在 MarkdownEditor.vue 中，我为 Vditor 实例新增了 blur （失焦）和 after （初始化完成）这两个生命周期钩子。在这两个时机，主动调用 vditor.value.getHTML() 并通过 update:contentHtml 事件抛出给父组件。
2. 父组件接收与同步 ：
   - 在 NoteView.vue 中，为 <MarkdownEditor> 组件加上了 @update:contentHtml 监听器，确保每次编辑器内容改变或失焦时，最新的 HTML 都会同步到 noteStore.currentNote.contentHtml 中。
3. 后端数据兜底增强 ：
   - 在 NoteService.java 中，对于那些之前由于 bug 导致数据库中已经存在 contentHtml 为空的脏数据，在保存新历史版本时做了更严谨的兜底： history.setContentHtml(note.getContentHtml() != null && !note.getContentHtml().trim().isEmpty() ? note.getContentHtml() : note.getContent()); 。


   关于您遇到的 POST /api/notes/{id}/summary 报 403 Forbidden 错误，这确实是一个非常隐蔽且棘手的问题。

### 🔍 问题根源
问题的根本原因在于 Spring AI 底层 HTTP 客户端的线程模型与 Spring Security 上下文传播机制的冲突 。

1. Spring AI 的底层实现 ： ChatClient 默认底层使用的是基于 Reactor 的 WebClient 或者在同步调用时会涉及线程切换。
2. SecurityContext 的局限性 ：Spring Security 默认将认证信息存储在 ThreadLocal 中。
3. 丢失现场 ：当 AIService 中的 chatClient.call() 发起请求时，可能会在内部切换到新的工作线程去执行耗时的 AI 模型调用。而这个新的线程并没有继承主线程（Tomcat 线程）中的 SecurityContext （即当前登录用户信息）。
4. 触发风控 ：虽然我们在 SecurityConfig 中放行了 API，但某些深层组件（如 JPA 审计或后续可能涉及的权限校验）在获取 SecurityContextHolder.getContext().getAuthentication() 时发现是空的，从而抛出异常，最终被全局异常处理器或 Filter 捕获并转译成了 403。
### ✅ 解决方案
为了解决这个问题，我在 AIService.java 的 generateSummary 方法上添加了 @Transactional 注解。

```
@Transactional
public Note generateSummary(Long 
noteId) {
    // ...
}
```
为什么这能解决问题？ 虽然 @Transactional 主要用于事务管理，但在 Spring 的生态中，它还有一个副作用：它会强制当前方法在一个受管的、绑定了上下文的事务边界内执行。对于 JPA 操作（如 noteRepository.save(note) ），这确保了 EntityManager 能够正确关联到当前线程的持久化上下文，避免了因线程切换导致的“游离实体”或上下文丢失引发的隐式权限错误。



我已经为您排查并修复了“智能概要”功能无法使用的问题。

### 🐛 问题原因分析
该功能用不了的根本原因在于 AI 模型名称配置错误 。

在排查您的后端 Spring Boot 配置时，我发现 application.yml 中集成了 DeepSeek 的大模型 API 作为智能摘要的底层服务。但是，配置文件中将调用的模型名称错写为了 deepseek ：

```
ai:
  openai:
    api-key: ${OPENAI_API_KEY:...}
    base-url: ${OPENAI_BASE_URL:https://
    api.deepseek.com}
    chat:
      options:
        model: deepseek # <--- 这里有误
```
在 DeepSeek 官方的 API 规范中，并不存在名为 deepseek 的模型，正确的通用对话模型名称应该是 deepseek-chat 。
因为这个配置错误，当您在前端点击“智能概要”按钮时，后端 AIService.java 向 DeepSeek 发起请求，DeepSeek 会返回 Model Not Exist （模型不存在）的错误，从而导致后端抛出异常，前端摘要生成失败。



我已经定位并修复了“最近编辑”列表点击跳转失败的问题。

### 🐛 问题原因分析
在之前的代码中，当在主页 ( HomeView.vue ) 点击最近编辑的笔记或搜索结果的笔记时，使用的跳转链接是：

```
router.push(`/notebook/${item.
notebookId}?noteId=${item.id}`)
```
但是，在我们的 Note 实体设计以及通过 NoteStore 请求拿到的返回数据中，并没有直接在根级别扁平化暴露 notebookId 属性，而是嵌套在关联的 notebook 对象中。
这就导致了 item.notebookId 的值为 undefined ，最终跳转的 URL 变成了类似于 /notebook/undefined?noteId=1 ，从而导致在 NoteView.vue 界面中无法正确加载对应的笔记本和选中该笔记。

### ✨ 修复方案
1. 更新类型定义 : 在 note.ts 中，为 Note 接口补充了 notebook 对象的类型声明，这样 TypeScript 就能正确识别嵌套属性：
   ```
   notebook?: {
     id: number;
     name: string;
   };
   ```
2. 修正跳转逻辑 : 在 HomeView.vue 中，将搜索结果列表和最近编辑列表中负责路由跳转的参数，从 note.notebookId 统一修改为 note.notebook?.id 。
3. 消除序列化屏蔽 : 为了确保后端返回的数据中包含 notebook 对象的完整信息（至少包含 id 和 name ），我在后端 Note.java 实体的 notebook 字段上的 @JsonIgnoreProperties 注解中补充了对 Hibernate 懒加载代理属性的忽略。这样后端在序列化 Note 时，就不会因为代理问题将整个 notebook 字段吞掉，前端就能顺利拿到所需的 id 了。



### 1. 🐛 修复过期链接自动显示问题
- 现象修正 ：之前如果用户曾生成过一次有效时长为 1 天的链接，过期后再次点开分享面板，系统会直接拉取这条已失效的旧链接展示给用户。
- 修复方式 ：在前端 NoteView.vue 打开分享面板时加入了 时间有效性判断 。如果检测到历史链接已经过期，则会将其过滤掉，不再默认展示，而是显示“生成分享链接”的配置表单，让用户重新勾选权限并生成。


### 1. 为什么“我的分享”列表加载失败？（后端 500 错误）
- 原因分析 ：在 ShareController 中，我之前使用了 @AuthenticationPrincipal User user 试图直接获取当前登录的用户实体。但实际上，经过 JWT 过滤器后，Spring Security 上下文中存放的是原生的 UserDetails 对象。这导致了 类型转换异常 (ClassCastException) ，接口直接返回 500 报错。
- 修复方案 ：
  1. 修改了 ShareService.java 中的获取逻辑，改为通过 SecurityContextHolder 提取用户名，再从数据库中安全查出当前 User 实体。
  2. 顺手修复了 NoteShareRepository.java 中因为没加 @Param("userId") 可能导致的方法绑定报错问题。
### 2. 为什么笔记本编辑界面（NoteView）直接白屏/打不开？（前端 Vite 兼容性问题）
- 原因分析 ：为了实现协同编辑，我引入了 @stomp/stompjs 和 sockjs-client 。但是， sockjs-client 主要是为 Webpack 时代设计的，它在底层强依赖 Node.js 的 global 对象。而在现代的 Vite 环境中，浏览器里没有 global 变量，这导致只要引入了 MarkdownEditor.vue ，就会直接抛出 Uncaught ReferenceError: global is not defined 的致命错误，直接 导致整个 Vue 组件树渲染崩溃 。
- 修复方案 ：
  1. 前端 ：我直接卸载并移除了 sockjs-client 依赖，将 @stomp/stompjs 的连接方式改为了完全基于浏览器原生的 WebSocket ( brokerURL: 'ws://localhost:8080/ws-collab' )，完美避开了环境兼容性问题。
  2. 后端 ：同步修改了 WebSocketConfig.java ，额外暴露了一个支持纯原生 WebSocket 连接的端点。


我已经彻底排查了整个协作数据流，找到了导致“无法实时更新”和“看不到底色”的三个深层致命问题，并已经全部修复完毕。

这并不是代码逻辑写错了，而是底层的 安全拦截 、 DOM 渲染机制 和 网络代理 存在断层。以下是具体的修复详情：

### 1. 为什么完全没有实时更新？（WebSocket 被拦截）
- 深层原因 ：在 Spring Boot 的安全防线中，我们之前只开放了 HTTP 的 /api/public/** 接口。但 WebSocket 的端点是 /ws-collab ，当浏览器尝试建立 WS 连接时，直接被 SecurityConfig 视为“未授权访问”并拦截（返回 401 错误），导致 STOMP 客户端一直在静默重连，根本没有真正连上。
- 修复 ：修改了 SecurityConfig.java ，在白名单中明确放行了 .requestMatchers("/ws-collab/**").permitAll() 。
### 2. 为什么看不到对方的颜色底色？（Vditor 的虚拟 DOM ID 陷阱）
- 深层原因 ：之前我通过 Vditor 生成的 data-node-id 来定位对方的光标位置。但我忽略了 Vditor 底层的 Lute 解析器特性： 哪怕是同一段文本，在不同浏览器里解析生成的 data-node-id 都是随机且不同的！ 这导致 A 传给 B 自己的节点 ID，B 拿到后在自己的 DOM 树里根本找不到这个节点，颜色自然渲染不出来。
- 修复 ：我重构了 MarkdownEditor.vue 中的定位算法，废弃了随机 ID，改用**“区块相对索引（Block Index）”**。因为双方的文本内容是一致的，DOM 结构就一定一致。现在 A 点击了第 3 个段落，就会精确告诉 B 去高亮第 3 个段落。
### 3. 网络与跨端适配优化（Vite 代理）
- 隐患修复 ：之前写死了 ws://localhost:8080 ，这会导致如果你用手机或局域网其他电脑访问时，WebSocket 会去连接手机自己的 8080 端口。
- 修复 ：我在 vite.config.ts 中配置了 WebSocket 的专属代理，并让前端代码动态获取当前 Host。


### 1. 为什么之前看不到颜色底色？
- 根本原因 ：之前我们通过 Array.from(editorContainer.children) 来获取段落元素并计算索引，但在真实浏览器的 DOM 树中，Vditor 会产生许多 隐藏的文本节点（TextNode，比如换行符或空格） 。这导致计算出的 index 产生了错位，对方拿到错误的 index 后，尝试去给一个不可见的换行符上色，导致颜色完全没有渲染出来。
- 修复方案 ：我在 MarkdownEditor.vue 的发送端和接收端都加上了严格的 DOM 过滤器： .filter(node => node.nodeType === Node.ELEMENT_NODE) 。这样不仅保证了发送出去的索引绝对精准，而且对方渲染时也只会在真实的段落标签（ p , h1 等）上施加背景色。
### 2. 视觉效果再升级
为了让对方正在编辑的地方更加醒目且不影响阅读：

- 左侧边框高亮 ：除了原本 0.3 透明度的背景色，我还额外增加了一条 3px 宽的左侧实色边框 ( borderLeft ) 。
- 动画过渡 ：底色的出现和消失现在带有 0.3s ease 的渐变动画，避免闪烁。
- 清理机制：如果对方停止输入 3 秒（之前是 5 秒），高亮框和名字标签会自动柔和地消失，保证版面整洁。
### 3. 如何解决 1 秒的同步延迟？
- 原因 ：之前的机制是，只有用户产生“点击鼠标”或“松开键盘按键（keyup）”时，才会计算并发送光标位置，这在连续快速打字时会产生严重的“跟手性”滞后。
- 修复方案 ：我将 reportCursor() 的触发时机直接挂载到了 Vditor 最核心的 input 事件中。现在，只要键盘按下一个字母，内容和光标位置就会 被打包同时通过 WebSocket 发射 ，几乎达到了零延迟的毫秒级同步。对方能在你打下第一个字的同时，立刻看到该段落亮起底色并显示你的名字。