# EduPath 前端

Vue 3 + TypeScript + Vite 应用，包含学生学习流程与教师工作台。前端只访问 Spring Boot 的 /api/* 统一接口，不直接请求 AI 服务。

当前资源生成入口提供六类资源：个性化讲义、思维导图、题库、代码实验、流程图和拓展阅读。资源中心按类别浏览，历史动画脚本数据仅用于兼容。

## 本地开发

先启动根目录的数据库、业务 API 和 AI 服务，再在此目录运行：

~~~bash
npm install
npm run dev
~~~

默认 Vite 开发服务位于 http://localhost:5173，API 请求代理到 http://localhost:8080。生产构建使用根目录的 Docker Compose 配置。

## 检查

~~~bash
npm run type-check
npm run build
npm run test:resources
npm run test:research
npm run test:real-api
~~~

接口变更应同步更新根目录 api-contract.md 与前端类型。长任务需保留 task_id、进度和失败状态的可见性。
