import { createApp } from 'vue';
import App from './App.vue';
import './styles.css';
import { initResearchTaskControl } from './research';

// 研究模式只在带有研究 URL 参数时生效，普通用户不会看到研究字段或额外 UI。
initResearchTaskControl();

createApp(App).mount('#app');
