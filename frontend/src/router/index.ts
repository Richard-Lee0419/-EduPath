import { createRouter, createWebHistory } from "vue-router";

import Courses from "@/pages/Courses.vue";
import Dashboard from "@/pages/Dashboard.vue";
import Evaluation from "@/pages/Evaluation.vue";
import KnowledgeUpload from "@/pages/KnowledgeUpload.vue";
import LearningPath from "@/pages/LearningPath.vue";
import Login from "@/pages/Login.vue";
import Profile from "@/pages/Profile.vue";
import ProfileChat from "@/pages/ProfileChat.vue";
import Quiz from "@/pages/Quiz.vue";
import ResourceCenter from "@/pages/ResourceCenter.vue";
import ResourceGenerate from "@/pages/ResourceGenerate.vue";
import Tutor from "@/pages/Tutor.vue";
import TeacherInsights from "@/pages/TeacherInsights.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", component: Dashboard, meta: { title: "演示总览" } },
    { path: "/login", component: Login, meta: { title: "登录" } },
    { path: "/profile-chat", component: ProfileChat, meta: { title: "画像对话" } },
    { path: "/profile", component: Profile, meta: { title: "学习画像" } },
    { path: "/courses", component: Courses, meta: { title: "课程知识" } },
    { path: "/knowledge-upload", component: KnowledgeUpload, meta: { title: "知识库上传" } },
    { path: "/resource-generate", component: ResourceGenerate, meta: { title: "资源生成" } },
    { path: "/resources", component: ResourceCenter, meta: { title: "资源中心" } },
    { path: "/learning-path", component: LearningPath, meta: { title: "学习路径" } },
    { path: "/tutor", component: Tutor, meta: { title: "智能辅导" } },
    { path: "/quiz", component: Quiz, meta: { title: "练习测试" } },
    { path: "/evaluation", component: Evaluation, meta: { title: "学习评估" } },
    { path: "/teacher-insights", component: TeacherInsights, meta: { title: "班级学习洞察" } },
  ],
});
