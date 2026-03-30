(function bootstrapApp() {
  const { createApp } = Vue;
  const STORAGE_KEY = "ai_code_helper_sessions_v1";

  const TOP_STATUS_LABEL = {
    idle: "待命",
    streaming: "生成中",
    done: "已完成",
    error: "错误",
    stopped: "已停止"
  };

  const MESSAGE_STATUS_LABEL = {
    streaming: "生成中",
    done: "完成",
    error: "失败",
    stopped: "已停止"
  };

  const PROMPT_TEMPLATES = [
    "请帮我解释这段代码的核心逻辑",
    "根据这个需求给我一份实现步骤",
    "把下面代码重构成更易维护的版本",
    "给我补一组关键单元测试"
  ];

  function uid() {
    return `${Date.now()}_${Math.random().toString(16).slice(2, 10)}`;
  }

  function nextMemoryId(sessions) {
    let candidate = (Math.floor(Date.now() / 1000) % 2000000000) + Math.floor(Math.random() * 1000);
    const used = new Set(sessions.map((item) => item.id));
    while (used.has(candidate)) {
      candidate += 1;
    }
    return candidate;
  }

  createApp({
    data() {
      return {
        sessions: [],
        activeSessionId: null,
        draftMessage: "",
        taskType: "general",
        searchMode: "auto",
        promptTemplates: PROMPT_TEMPLATES,
        statusTextMap: MESSAGE_STATUS_LABEL,
        status: "idle",
        generating: false,
        activeController: null,
        requestMeta: {
          memoryId: null,
          message: "",
          taskType: "general",
          searchMode: "auto",
          startedAt: null,
          duration: 0,
          state: "idle"
        },
        viewportWidth: window.innerWidth || 1200,
        isSessionDrawerOpen: false,
        isContextDrawerOpen: false,
        now: Date.now(),
        timeTicker: null,
        knowledgeMaxFiles: 10,
        knowledgeFiles: [],
        selectedKnowledgeFile: null,
        selectedKnowledgeFileName: "",
        knowledgeUploading: false,
        knowledgeTask: {
          taskId: "",
          status: "",
          stage: "",
          progress: 0,
          message: "",
          uploadedFile: null,
          replacedFile: null
        },
        knowledgePollTimer: null,
        knowledgePolling: false
      };
    },
    computed: {
      sortedSessions() {
        return this.sessions.slice().sort((a, b) => b.updatedAt - a.updatedAt);
      },
      activeSession() {
        return this.sessions.find((item) => item.id === this.activeSessionId) || null;
      },
      activeMessages() {
        return this.activeSession ? this.activeSession.messages : [];
      },
      canSend() {
        return this.draftMessage.trim().length > 0 && !this.generating && !!this.activeSession;
      },
      canRegenerate() {
        return this.activeMessages.some((item) => item.role === "user");
      },
      userMessageCount() {
        return this.activeMessages.filter((item) => item.role === "user").length;
      },
      assistantMessageCount() {
        return this.activeMessages.filter((item) => item.role === "assistant").length;
      },
      statusLabel() {
        return TOP_STATUS_LABEL[this.status] || this.status;
      },
      statusClass() {
        return this.status;
      },
      isMobile() {
        return this.viewportWidth <= 860;
      },
      isTablet() {
        return this.viewportWidth <= 1100;
      },
      showBackdrop() {
        if (this.isMobile) {
          return this.isSessionDrawerOpen || this.isContextDrawerOpen;
        }
        return this.isTablet && this.isContextDrawerOpen;
      },
      sessionsPanelClass() {
        if (!this.isMobile) {
          return {};
        }
        return {
          drawer: true,
          "drawer-hidden": !this.isSessionDrawerOpen
        };
      },
      contextPanelClass() {
        if (!this.isTablet) {
          return {};
        }
        return {
          drawer: true,
          "drawer-hidden": !this.isContextDrawerOpen
        };
      },
      workspaceClass() {
        return {
          mobile: this.isMobile,
          tablet: this.isTablet
        };
      }
    },
    watch: {
      activeSessionId() {
        this.$nextTick(() => this.scrollChatToBottom());
      },
      activeMessages: {
        deep: true,
        handler() {
          this.$nextTick(() => this.scrollChatToBottom());
        }
      }
    },
    mounted() {
      this.loadSessions();
      if (!this.sessions.length) {
        this.createSession();
      } else if (!this.activeSessionId) {
        this.activeSessionId = this.sortedSessions[0].id;
      }
      window.addEventListener("resize", this.handleResize);
      this.timeTicker = window.setInterval(() => {
        this.now = Date.now();
      }, 30000);
      this.$nextTick(() => this.scrollChatToBottom());
      this.fetchKnowledgeFiles();
    },
    beforeUnmount() {
      window.removeEventListener("resize", this.handleResize);
      if (this.timeTicker) {
        window.clearInterval(this.timeTicker);
      }
      if (this.activeController) {
        this.activeController.abort();
      }
      this.stopKnowledgeTaskPolling();
    },
    methods: {
      loadSessions() {
        try {
          const raw = localStorage.getItem(STORAGE_KEY);
          if (!raw) {
            return;
          }
          const parsed = JSON.parse(raw);
          if (!Array.isArray(parsed)) {
            return;
          }
          this.sessions = parsed
            .map((session) => this.normalizeSession(session))
            .filter((session) => session && Number.isInteger(session.id));
          this.activeSessionId = this.sessions.length ? this.sortedSessions[0].id : null;
        } catch (error) {
          console.warn("load sessions failed", error);
          this.sessions = [];
          this.activeSessionId = null;
        }
      },
      normalizeSession(session) {
        if (!session || !Array.isArray(session.messages)) {
          return null;
        }
        return {
          id: Number(session.id),
          name: session.name || `会话 ${session.id}`,
          createdAt: Number(session.createdAt) || Date.now(),
          updatedAt: Number(session.updatedAt) || Date.now(),
          messages: session.messages
            .filter((message) => message && typeof message.content === "string")
            .map((message) => ({
              id: message.id || uid(),
              role: message.role === "user" ? "user" : "assistant",
              content: message.content,
              createdAt: Number(message.createdAt) || Date.now(),
              status: message.status || null
            }))
        };
      },
      persistSessions() {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(this.sessions));
      },
      handleResize() {
        this.viewportWidth = window.innerWidth || 1200;
        if (!this.isMobile) {
          this.isSessionDrawerOpen = false;
        }
        if (!this.isTablet) {
          this.isContextDrawerOpen = false;
        }
      },
      closeDrawers() {
        this.isSessionDrawerOpen = false;
        this.isContextDrawerOpen = false;
      },
      toggleSessionDrawer() {
        this.isSessionDrawerOpen = !this.isSessionDrawerOpen;
        if (this.isSessionDrawerOpen) {
          this.isContextDrawerOpen = false;
        }
      },
      toggleContextDrawer() {
        this.isContextDrawerOpen = !this.isContextDrawerOpen;
        if (this.isContextDrawerOpen) {
          this.isSessionDrawerOpen = false;
        }
      },
      createSession() {
        const createdAt = Date.now();
        const memoryId = nextMemoryId(this.sessions);
        const nextIndex = this.sessions.length + 1;
        const session = {
          id: memoryId,
          name: `新会话 ${nextIndex}`,
          createdAt,
          updatedAt: createdAt,
          messages: []
        };
        this.sessions.unshift(session);
        this.activeSessionId = session.id;
        this.status = "idle";
        this.requestMeta = {
          memoryId: null,
          message: "",
          taskType: this.taskType,
          searchMode: this.searchMode,
          startedAt: null,
          duration: 0,
          state: "idle"
        };
        this.persistSessions();
        this.closeDrawers();
      },
      selectSession(sessionId) {
        if (this.generating && sessionId !== this.activeSessionId) {
          this.stopStreaming("切换会话，已停止生成");
        }
        this.activeSessionId = sessionId;
        this.closeDrawers();
      },
      renameActiveSession() {
        if (!this.activeSession) {
          return;
        }
        const nextName = window.prompt("请输入会话名称", this.activeSession.name);
        if (!nextName) {
          return;
        }
        this.activeSession.name = nextName.trim().slice(0, 30) || this.activeSession.name;
        this.activeSession.updatedAt = Date.now();
        this.persistSessions();
      },
      removeActiveSession() {
        if (!this.activeSession) {
          return;
        }
        const ok = window.confirm(`确定删除会话「${this.activeSession.name}」吗？`);
        if (!ok) {
          return;
        }
        if (this.generating) {
          this.stopStreaming("会话删除，已停止生成");
        }
        const toDelete = this.activeSession.id;
        this.sessions = this.sessions.filter((item) => item.id !== toDelete);
        if (!this.sessions.length) {
          this.activeSessionId = null;
          this.createSession();
          return;
        }
        this.activeSessionId = this.sortedSessions[0].id;
        this.persistSessions();
      },
      clearActiveMessages() {
        if (!this.activeSession || this.generating) {
          return;
        }
        const ok = window.confirm("确定清空当前会话中的消息吗？");
        if (!ok) {
          return;
        }
        this.activeSession.messages = [];
        this.activeSession.updatedAt = Date.now();
        this.persistSessions();
      },
      sessionPreview(session) {
        const latest = session.messages[session.messages.length - 1];
        if (!latest) {
          return "暂无消息";
        }
        const text = latest.content.replace(/\s+/g, " ").trim();
        return text.length > 28 ? `${text.slice(0, 28)}...` : text;
      },
      applyPrompt(prompt) {
        this.draftMessage = prompt;
      },
      onComposerKeydown(event) {
        if (event.key === "Enter" && !event.shiftKey) {
          event.preventDefault();
          this.sendMessage();
        }
      },
      async sendMessage() {
        const text = this.draftMessage.trim();
        if (!text || !this.activeSession || this.generating) {
          return;
        }

        const now = Date.now();
        const userMessage = {
          id: uid(),
          role: "user",
          content: text,
          createdAt: now
        };
        const assistantMessage = {
          id: uid(),
          role: "assistant",
          content: "",
          createdAt: Date.now(),
          status: "streaming"
        };

        this.activeSession.messages.push(userMessage);
        this.activeSession.messages.push(assistantMessage);
        if (this.activeSession.name.startsWith("新会话")) {
          this.activeSession.name = text.slice(0, 14) || this.activeSession.name;
        }
        this.activeSession.updatedAt = Date.now();
        this.draftMessage = "";
        this.generating = true;
        this.status = "streaming";
        const currentTaskType = this.taskType;
        const currentSearchMode = this.searchMode;
        this.requestMeta = {
          memoryId: this.activeSession.id,
          message: text,
          taskType: currentTaskType,
          searchMode: currentSearchMode,
          startedAt: Date.now(),
          duration: 0,
          state: "streaming"
        };
        this.persistSessions();

        await this.streamAssistantReply(
          this.activeSession,
          text,
          assistantMessage,
          currentSearchMode,
          currentTaskType
        );
      },
      async streamAssistantReply(session, text, assistantMessage, searchMode, taskType) {
        const startedAt = performance.now();
        const controller = new AbortController();
        this.activeController = controller;

        try {
          const query = new URLSearchParams({
            memoryId: String(session.id),
            message: text,
            searchMode: searchMode || "auto",
            taskType: taskType || "general"
          });
          const response = await fetch(`/ai/chat?${query.toString()}`, {
            method: "GET",
            headers: {
              Accept: "text/event-stream"
            },
            cache: "no-store",
            signal: controller.signal
          });
          if (!response.ok || !response.body) {
            throw new Error(`服务响应异常: ${response.status}`);
          }

          const reader = response.body.getReader();
          const decoder = new TextDecoder("utf-8");
          let buffer = "";

          while (true) {
            const { value, done } = await reader.read();
            if (done) {
              break;
            }
            buffer += decoder.decode(value, { stream: true }).replace(/\r/g, "");
            let boundary = buffer.indexOf("\n\n");
            while (boundary !== -1) {
              const block = buffer.slice(0, boundary);
              buffer = buffer.slice(boundary + 2);
              this.consumeSseBlock(block, assistantMessage);
              boundary = buffer.indexOf("\n\n");
            }
          }

          buffer += decoder.decode().replace(/\r/g, "");
          if (buffer.trim()) {
            this.consumeSseBlock(buffer, assistantMessage);
          }

          if (!assistantMessage.content.trim()) {
            assistantMessage.content = "（未收到内容，请重试）";
          }
          assistantMessage.status = "done";
          this.status = "done";
          this.requestMeta.duration = Math.round(performance.now() - startedAt);
          this.requestMeta.state = "done";
        } catch (error) {
          if (error && error.name === "AbortError") {
            assistantMessage.status = "stopped";
            if (!assistantMessage.content.trim()) {
              assistantMessage.content = "（已停止生成）";
            }
            this.status = "stopped";
            this.requestMeta.state = "stopped";
          } else {
            assistantMessage.status = "error";
            assistantMessage.content = assistantMessage.content || "请求失败，请稍后重试。";
            this.status = "error";
            this.requestMeta.state = "error";
          }
        } finally {
          this.generating = false;
          this.activeController = null;
          session.updatedAt = Date.now();
          this.persistSessions();
          this.$nextTick(() => this.scrollChatToBottom());
        }
      },
      consumeSseBlock(block, assistantMessage) {
        if (!block) {
          return;
        }
        const lines = block.split("\n");
        const payloadParts = lines
          .filter((line) => line.startsWith("data:"))
          .map((line) => line.slice(5).replace(/^ /, ""));
        if (!payloadParts.length) {
          return;
        }
        const payload = payloadParts.join("\n");
        if (payload === "[DONE]") {
          return;
        }
        assistantMessage.content += payload;
      },
      stopStreaming(nextStatus) {
        if (!this.generating || !this.activeController) {
          return;
        }
        this.activeController.abort();
        if (nextStatus) {
          this.status = "stopped";
        }
      },
      regenerateLast() {
        if (!this.activeSession || this.generating) {
          return;
        }
        const userMessages = this.activeMessages.filter((item) => item.role === "user");
        const last = userMessages[userMessages.length - 1];
        if (!last) {
          return;
        }
        this.draftMessage = last.content;
        this.sendMessage();
      },
      splitMessageBlocks(content) {
        if (!content) {
          return [];
        }
        const blocks = [];
        const pattern = /```([a-zA-Z0-9_#+.-]*)?\n([\s\S]*?)```/g;
        let cursor = 0;
        let match = pattern.exec(content);
        while (match) {
          if (match.index > cursor) {
            blocks.push({
              type: "text",
              content: content.slice(cursor, match.index)
            });
          }
          blocks.push({
            type: "code",
            lang: match[1] || "code",
            content: match[2]
          });
          cursor = pattern.lastIndex;
          match = pattern.exec(content);
        }
        if (cursor < content.length) {
          blocks.push({
            type: "text",
            content: content.slice(cursor)
          });
        }
        if (!blocks.length) {
          blocks.push({
            type: "text",
            content
          });
        }
        return blocks;
      },
      formatTextBlock(content) {
        if (!content) {
          return "";
        }
        let text = content;
        text = text.replace(/\[(ASSISTANT ROLE|OUTPUT CONTRACT|SEARCH MODE|TOOL POLICY|TASK TYPE|QUALITY RULES|USER QUESTION)\]\s*/gi, "");
        text = text.replace(/^#{1,6}\s*/gm, "");
        text = text.replace(/^\s*>\s?/gm, "");
        text = text.replace(/^\s*[-*]\s+/gm, "* ");
        text = text.replace(/\*\*(.*?)\*\*/g, "$1");
        text = text.replace(/__(.*?)__/g, "$1");
        text = text.replace(/`([^`]+)`/g, "$1");
        text = text.replace(/~{3,}/g, "");
        text = text.replace(/\n{3,}/g, "\n\n");
        return text.trim();
      },
      formatClock(timestamp) {
        if (!timestamp) {
          return "--:--:--";
        }
        const date = new Date(timestamp);
        const hh = String(date.getHours()).padStart(2, "0");
        const mm = String(date.getMinutes()).padStart(2, "0");
        const ss = String(date.getSeconds()).padStart(2, "0");
        return `${hh}:${mm}:${ss}`;
      },
      formatRelativeTime(timestamp) {
        if (!timestamp) {
          return "-";
        }
        const diff = Math.max(0, this.now - timestamp);
        const minute = 60 * 1000;
        const hour = 60 * minute;
        const day = 24 * hour;
        if (diff < minute) {
          return "刚刚";
        }
        if (diff < hour) {
          return `${Math.floor(diff / minute)} 分钟前`;
        }
        if (diff < day) {
          return `${Math.floor(diff / hour)} 小时前`;
        }
        return `${Math.floor(diff / day)} 天前`;
      },
      searchModeLabel(mode) {
        if (mode === "force") {
          return "强制联网";
        }
        if (mode === "off") {
          return "关闭联网";
        }
        return "自动";
      },
      taskTypeLabel(type) {
        if (type === "topic") {
          return "选题";
        }
        if (type === "literature") {
          return "文献综述";
        }
        if (type === "method") {
          return "方法设计";
        }
        if (type === "experiment") {
          return "实验设计";
        }
        if (type === "polish") {
          return "学术润色";
        }
        return "通用";
      },
      formatFileSize(sizeBytes) {
        if (!sizeBytes || sizeBytes < 1024) {
          return `${sizeBytes || 0} B`;
        }
        const kb = sizeBytes / 1024;
        if (kb < 1024) {
          return `${kb.toFixed(1)} KB`;
        }
        const mb = kb / 1024;
        return `${mb.toFixed(2)} MB`;
      },
      scrollChatToBottom() {
        const element = this.$refs.chatScroll;
        if (!element) {
          return;
        }
        element.scrollTop = element.scrollHeight;
      },
      onKnowledgeFileChange(event) {
        const file = event?.target?.files?.[0] || null;
        this.selectedKnowledgeFile = file;
        this.selectedKnowledgeFileName = file ? file.name : "";
      },
      async fetchKnowledgeFiles() {
        try {
          const response = await fetch("/rag/files", {
            method: "GET",
            cache: "no-store"
          });
          if (!response.ok) {
            throw new Error(`load failed: ${response.status}`);
          }
          const data = await response.json();
          this.knowledgeMaxFiles = Number(data.maxFiles || 10);
          this.knowledgeFiles = Array.isArray(data.files) ? data.files : [];
        } catch (error) {
          console.warn("fetch knowledge files failed", error);
        }
      },
      async uploadKnowledgeFile() {
        if (!this.selectedKnowledgeFile || this.knowledgeUploading) {
          return;
        }
        this.knowledgeUploading = true;
        const formData = new FormData();
        formData.append("file", this.selectedKnowledgeFile);
        try {
          const response = await fetch("/rag/files", {
            method: "POST",
            body: formData
          });
          const data = await response.json();
          if (!response.ok) {
            throw new Error(data.message || `upload failed: ${response.status}`);
          }
          this.knowledgeTask = {
            taskId: data.taskId || "",
            status: data.status || "",
            stage: data.stage || "",
            progress: Number(data.progress || 0),
            message: data.message || "",
            uploadedFile: data.uploadedFile || null,
            replacedFile: data.replacedFile || null
          };
          this.startKnowledgeTaskPolling(this.knowledgeTask.taskId);
          this.selectedKnowledgeFile = null;
          this.selectedKnowledgeFileName = "";
        } catch (error) {
          this.knowledgeUploading = false;
          this.knowledgeTask = {
            taskId: "",
            status: "FAILED",
            stage: "FAILED",
            progress: 100,
            message: error?.message || "上传失败",
            uploadedFile: null,
            replacedFile: null
          };
        }
      },
      startKnowledgeTaskPolling(taskId) {
        if (!taskId) {
          this.knowledgeUploading = false;
          return;
        }
        this.stopKnowledgeTaskPolling();
        this.pollKnowledgeTaskOnce(taskId);
        this.knowledgePollTimer = window.setInterval(() => {
          this.pollKnowledgeTaskOnce(taskId);
        }, 1500);
      },
      stopKnowledgeTaskPolling() {
        if (this.knowledgePollTimer) {
          window.clearInterval(this.knowledgePollTimer);
          this.knowledgePollTimer = null;
        }
        this.knowledgePolling = false;
      },
      async pollKnowledgeTaskOnce(taskId) {
        if (!taskId || this.knowledgePolling) {
          return;
        }
        this.knowledgePolling = true;
        try {
          const response = await fetch(`/rag/tasks/${encodeURIComponent(taskId)}`, {
            method: "GET",
            cache: "no-store"
          });
          if (!response.ok) {
            throw new Error(`poll failed: ${response.status}`);
          }
          const data = await response.json();
          this.knowledgeTask = {
            taskId: data.taskId || taskId,
            status: data.status || "",
            stage: data.stage || "",
            progress: Number(data.progress || 0),
            message: data.message || "",
            uploadedFile: data.uploadedFile || null,
            replacedFile: data.replacedFile || null
          };
          const finished = data.status === "SUCCESS" || data.status === "FAILED";
          if (finished) {
            this.knowledgeUploading = false;
            this.stopKnowledgeTaskPolling();
            await this.fetchKnowledgeFiles();
          }
        } catch (error) {
          this.knowledgeUploading = false;
          this.stopKnowledgeTaskPolling();
          this.knowledgeTask = {
            taskId,
            status: "FAILED",
            stage: "FAILED",
            progress: 100,
            message: error?.message || "任务轮询失败",
            uploadedFile: null,
            replacedFile: null
          };
        } finally {
          this.knowledgePolling = false;
        }
      }
    }
  }).mount("#app");
})();
