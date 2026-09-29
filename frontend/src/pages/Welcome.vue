<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue';
import type { PageKey } from '../types';

const props = defineProps<{
  isAuthenticated: boolean;
}>();

const emit = defineEmits<{
  navigate: [page: PageKey];
}>();

const loginNotice = ref('');
let noticeTimer: ReturnType<typeof setTimeout> | undefined;

const enterAuth = () => {
  emit('navigate', 'login');
};

const enterProject = () => {
  if (props.isAuthenticated) {
    emit('navigate', 'dashboard');
    return;
  }

  loginNotice.value = '请先通过右下角的“登录 / 注册”完成登录';
  if (noticeTimer) {
    window.clearTimeout(noticeTimer);
  }
  noticeTimer = window.setTimeout(() => {
    loginNotice.value = '';
  }, 3200);
};

onBeforeUnmount(() => {
  if (noticeTimer) {
    window.clearTimeout(noticeTimer);
  }
});
</script>

<template>
  <main class="welcome-page" aria-labelledby="welcome-title">
    <div class="welcome-page__atmosphere" aria-hidden="true"></div>

    <div class="welcome-orbit" aria-hidden="true">
      <div class="welcome-orbit__layer welcome-orbit__layer--outer"></div>
      <div class="welcome-orbit__layer welcome-orbit__layer--middle"></div>
      <div class="welcome-orbit__layer welcome-orbit__layer--inner"></div>
      <div class="welcome-orbit__core"></div>
    </div>

    <section class="welcome-hero">
      <h1 id="welcome-title" class="welcome-hero__title">
        <span>知径</span>
        <span class="welcome-hero__english">EduPath</span>
      </h1>
      <p class="welcome-hero__tagline">让每一次学习，都有一条更适合你的路径</p>

      <div class="welcome-resume-zone">
        <button class="welcome-resume" type="button" @click="enterProject">
          <span>直接进入项目</span>
          <span class="welcome-resume__arrow" aria-hidden="true">↗</span>
        </button>
        <div class="welcome-resume__status" role="status" aria-live="polite">
          <Transition name="welcome-notice">
            <p v-if="loginNotice" class="welcome-login-notice">{{ loginNotice }}</p>
          </Transition>
        </div>
      </div>
    </section>

    <section class="welcome-about" aria-labelledby="welcome-about-title">
      <h2 id="welcome-about-title">关于知径</h2>
      <p>
        知径 EduPath 是一个基于大模型与多智能体协同的个性化学习平台，<br />
        通过学习画像、资源生成、路径规划、智能辅导与学习评估，<br />
        为学习者提供持续更新的专属学习体验。
      </p>
    </section>

    <button class="welcome-entry" type="button" aria-label="进入登录或注册页面" @click="enterAuth">
      <span>登录 / 注册</span>
      <span class="welcome-entry__arrow" aria-hidden="true">→</span>
    </button>
  </main>
</template>

<style scoped>
.welcome-page {
  position: relative;
  isolation: isolate;
  min-height: 100vh;
  min-height: 100svh;
  overflow: hidden;
  color: #f8f4e9;
  background:
    radial-gradient(circle at 16% 18%, rgba(20, 126, 110, 0.2), transparent 34%),
    radial-gradient(circle at 82% 82%, rgba(54, 111, 146, 0.18), transparent 38%),
    linear-gradient(138deg, #061512 0%, #092621 48%, #081c22 100%);
}

.welcome-page::before {
  content: '';
  position: absolute;
  z-index: -2;
  inset: 0;
  background:
    linear-gradient(108deg, rgba(255, 255, 255, 0.025), transparent 28% 72%, rgba(226, 169, 73, 0.035)),
    repeating-linear-gradient(116deg, transparent 0 104px, rgba(228, 242, 235, 0.018) 105px 106px);
  mask-image: linear-gradient(to bottom, black, transparent 92%);
  pointer-events: none;
}

.welcome-page::after {
  content: '';
  position: absolute;
  z-index: 5;
  inset: 0;
  background: radial-gradient(circle at center, transparent 18%, rgba(3, 13, 12, 0.12) 62%, rgba(3, 10, 10, 0.54) 112%);
  pointer-events: none;
}

.welcome-page__atmosphere {
  position: absolute;
  z-index: -1;
  inset: -18%;
  background:
    radial-gradient(ellipse at 54% 48%, rgba(51, 152, 139, 0.12), transparent 32%),
    radial-gradient(ellipse at 42% 62%, rgba(222, 157, 55, 0.08), transparent 27%);
  filter: blur(44px);
}

.welcome-orbit {
  position: absolute;
  z-index: 0;
  top: 47%;
  left: 50%;
  width: clamp(560px, 62vw, 980px);
  aspect-ratio: 1;
  transform: translate(-50%, -50%);
  pointer-events: none;
}

.welcome-orbit__layer,
.welcome-orbit__core {
  position: absolute;
  will-change: transform, border-radius, opacity;
}

.welcome-orbit__layer {
  border-radius: 48% 52% 58% 42% / 52% 44% 56% 48%;
  -webkit-mask-image: radial-gradient(ellipse at center, transparent 0 35%, #000 44% 72%, transparent 83%);
  mask-image: radial-gradient(ellipse at center, transparent 0 35%, #000 44% 72%, transparent 83%);
  transform-origin: 49% 52%;
}

.welcome-orbit__layer--outer {
  inset: 2%;
  background: conic-gradient(
    from 20deg,
    rgba(57, 155, 151, 0.72),
    rgba(64, 142, 183, 0.66) 21%,
    rgba(230, 181, 85, 0.6) 46%,
    rgba(229, 131, 77, 0.46) 59%,
    rgba(33, 133, 113, 0.72) 82%,
    rgba(57, 155, 151, 0.72)
  );
  filter: blur(34px) saturate(112%);
  opacity: 0.72;
  animation: welcome-orbit-turn 20s linear infinite;
}

.welcome-orbit__layer--middle {
  inset: 9%;
  background: conic-gradient(
    from 210deg,
    rgba(41, 143, 124, 0.78),
    rgba(219, 166, 67, 0.68) 28%,
    rgba(68, 145, 175, 0.66) 55%,
    rgba(234, 139, 78, 0.45) 75%,
    rgba(41, 143, 124, 0.78)
  );
  filter: blur(22px);
  opacity: 0.78;
  animation: welcome-orbit-drift 16s cubic-bezier(0.42, 0, 0.28, 1) infinite alternate;
}

.welcome-orbit__layer--inner {
  inset: 19%;
  background: conic-gradient(
    from 92deg,
    rgba(83, 164, 190, 0.52),
    rgba(27, 129, 111, 0.72) 31%,
    rgba(237, 187, 90, 0.6) 61%,
    rgba(222, 125, 73, 0.4) 78%,
    rgba(83, 164, 190, 0.52)
  );
  filter: blur(18px);
  opacity: 0.7;
  animation: welcome-orbit-breathe 13s ease-in-out infinite;
}

.welcome-orbit__core {
  inset: 28%;
  border-radius: 46% 54% 42% 58% / 58% 46% 54% 42%;
  background:
    radial-gradient(circle at 34% 28%, rgba(85, 177, 166, 0.24), transparent 32%),
    radial-gradient(circle at 72% 68%, rgba(222, 166, 76, 0.16), transparent 38%),
    rgba(5, 24, 22, 0.7);
  box-shadow:
    inset 0 0 90px rgba(50, 143, 129, 0.08),
    0 0 90px rgba(23, 102, 91, 0.12);
  filter: blur(7px);
  animation: welcome-core-float 17s ease-in-out infinite alternate;
}

.welcome-hero {
  position: absolute;
  z-index: 10;
  top: 46%;
  left: 50%;
  width: min(92vw, 1040px);
  text-align: center;
  transform: translate(-50%, -50%);
  animation: welcome-content-in 720ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.welcome-hero__title {
  display: flex;
  width: 100%;
  max-width: none;
  align-items: baseline;
  justify-content: center;
  gap: clamp(16px, 2.2vw, 34px);
  margin: 0 auto;
  color: #fffaf0;
  font-size: clamp(3.4rem, 7.6vw, 8.4rem);
  font-weight: 760;
  letter-spacing: -0.045em;
  line-height: 0.94;
  text-wrap: nowrap;
  text-shadow:
    0 4px 34px rgba(9, 31, 27, 0.68),
    0 0 54px rgba(219, 239, 231, 0.1);
}

.welcome-hero__english {
  font-weight: 620;
  letter-spacing: -0.055em;
  background: linear-gradient(104deg, #fffaf0 18%, #dff3eb 58%, #f3d89d 104%);
  background-clip: text;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.welcome-hero__tagline {
  margin: clamp(20px, 3.2vh, 34px) 0 0;
  color: rgba(240, 245, 239, 0.82);
  font-size: clamp(1rem, 1.35vw, 1.28rem);
  font-weight: 420;
  letter-spacing: 0.16em;
  text-shadow: 0 4px 22px rgba(3, 13, 12, 0.8);
}

.welcome-resume-zone {
  display: grid;
  min-height: 92px;
  justify-items: center;
  margin-top: clamp(34px, 5vh, 54px);
}

.welcome-resume {
  display: inline-flex;
  min-width: 188px;
  min-height: 52px;
  align-items: center;
  justify-content: center;
  gap: 20px;
  padding: 0 24px;
  border: 1px solid rgba(226, 239, 232, 0.32);
  border-radius: 999px;
  color: rgba(247, 244, 233, 0.9);
  background: rgba(6, 27, 24, 0.14);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.055),
    0 10px 34px rgba(2, 13, 11, 0.14);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  font-size: 0.94rem;
  font-weight: 620;
  letter-spacing: 0.1em;
  transition:
    color 220ms ease,
    border-color 220ms ease,
    background-color 220ms ease,
    box-shadow 220ms ease,
    transform 220ms ease;
}

.welcome-resume__arrow {
  color: #e9ce8a;
  font-size: 1.08rem;
  transition: transform 220ms ease;
}

.welcome-resume:hover {
  border-color: rgba(230, 204, 139, 0.72);
  color: #fffaf0;
  background: rgba(21, 73, 63, 0.38);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.09),
    0 14px 40px rgba(2, 13, 11, 0.23);
  transform: translateY(-2px) scale(1.02);
}

.welcome-resume:hover .welcome-resume__arrow {
  transform: translate(3px, -2px);
}

.welcome-resume:focus-visible {
  outline: 3px solid rgba(247, 218, 149, 0.88);
  outline-offset: 5px;
  border-color: #efd596;
}

.welcome-resume:active {
  transform: translateY(0) scale(0.99);
}

.welcome-resume__status {
  display: grid;
  min-height: 32px;
  place-items: center;
}

.welcome-login-notice {
  margin: 10px 0 0;
  padding: 7px 14px;
  border: 1px solid rgba(235, 196, 118, 0.25);
  border-radius: 999px;
  color: #f0dba7;
  background: rgba(30, 49, 41, 0.56);
  box-shadow: 0 10px 28px rgba(2, 12, 10, 0.16);
  font-size: 0.84rem;
  letter-spacing: 0.035em;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.welcome-notice-enter-active,
.welcome-notice-leave-active {
  transition:
    opacity 180ms ease,
    transform 180ms ease;
}

.welcome-notice-enter-from,
.welcome-notice-leave-to {
  opacity: 0;
  transform: translateY(-5px);
}

.welcome-about {
  position: absolute;
  z-index: 10;
  bottom: clamp(36px, 6vh, 68px);
  left: clamp(28px, 5vw, 76px);
  width: min(590px, 48vw);
  padding: 18px 22px 18px 24px;
  border-left: 1px solid rgba(210, 236, 226, 0.54);
  border-radius: 0 18px 18px 0;
  background: linear-gradient(90deg, rgba(7, 29, 26, 0.5), rgba(10, 35, 32, 0.2) 72%, transparent);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.035);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  animation: welcome-content-in 720ms 160ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.welcome-about h2 {
  margin: 0 0 9px;
  color: #f5ead1;
  font-size: clamp(1.12rem, 1.5vw, 1.34rem);
  font-weight: 680;
  letter-spacing: 0.06em;
}

.welcome-about p {
  margin: 0;
  color: rgba(229, 240, 234, 0.78);
  font-size: clamp(0.94rem, 1.02vw, 1.06rem);
  line-height: 1.78;
}

.welcome-entry {
  position: absolute;
  z-index: 10;
  right: clamp(28px, 5vw, 76px);
  bottom: clamp(42px, 6.5vh, 74px);
  display: inline-flex;
  min-width: 190px;
  min-height: 62px;
  align-items: center;
  justify-content: center;
  gap: 24px;
  padding: 0 27px;
  border: 1px solid rgba(220, 239, 229, 0.64);
  border-radius: 999px;
  color: #f7f1e5;
  background: rgba(10, 42, 37, 0.34);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.09),
    0 14px 38px rgba(2, 12, 11, 0.24);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  font-size: 1rem;
  font-weight: 650;
  letter-spacing: 0.08em;
  transition:
    color 240ms ease,
    background-color 240ms ease,
    border-color 240ms ease,
    box-shadow 240ms ease,
    transform 240ms ease;
  animation: welcome-content-in 720ms 260ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.welcome-entry__arrow {
  font-size: 1.35rem;
  line-height: 1;
  transition: transform 240ms ease;
}

.welcome-entry:hover {
  border-color: #e4c987;
  color: #0b4037;
  background: rgba(250, 240, 211, 0.93);
  box-shadow:
    0 18px 46px rgba(2, 12, 11, 0.32),
    0 0 0 5px rgba(226, 192, 112, 0.07);
  transform: scale(1.035);
}

.welcome-entry:hover .welcome-entry__arrow {
  transform: translateX(5px);
}

.welcome-entry:focus-visible {
  outline: 3px solid rgba(247, 218, 149, 0.9);
  outline-offset: 5px;
  border-color: #f0d99f;
}

.welcome-entry:active {
  transform: scale(0.99);
}

@keyframes welcome-orbit-turn {
  0% {
    border-radius: 48% 52% 58% 42% / 52% 44% 56% 48%;
    transform: rotate(0deg) scale(0.98);
  }
  50% {
    border-radius: 57% 43% 48% 52% / 43% 57% 46% 54%;
    transform: rotate(180deg) scale(1.04);
  }
  100% {
    border-radius: 48% 52% 58% 42% / 52% 44% 56% 48%;
    transform: rotate(360deg) scale(0.98);
  }
}

@keyframes welcome-orbit-drift {
  0% {
    border-radius: 42% 58% 46% 54% / 57% 42% 58% 43%;
    transform: translate3d(-2%, 1%, 0) rotate(-14deg) scale(0.97);
  }
  52% {
    border-radius: 58% 42% 56% 44% / 44% 58% 42% 56%;
    transform: translate3d(3%, -2%, 0) rotate(24deg) scale(1.06);
  }
  100% {
    border-radius: 50% 50% 41% 59% / 59% 45% 55% 41%;
    transform: translate3d(-1%, 2%, 0) rotate(46deg) scale(1.01);
  }
}

@keyframes welcome-orbit-breathe {
  0%,
  100% {
    transform: rotate(8deg) scale(0.94);
    opacity: 0.58;
  }
  50% {
    transform: rotate(-24deg) scale(1.08);
    opacity: 0.78;
  }
}

@keyframes welcome-core-float {
  from {
    transform: translate3d(-2%, 1%, 0) rotate(-5deg) scale(0.98);
  }
  to {
    transform: translate3d(2%, -2%, 0) rotate(8deg) scale(1.04);
  }
}

@keyframes welcome-content-in {
  from {
    opacity: 0;
    transform: translate(-50%, calc(-50% + 14px));
  }
  to {
    opacity: 1;
    transform: translate(-50%, -50%);
  }
}

.welcome-about,
.welcome-entry {
  --welcome-rest-transform: translateY(0);
}

.welcome-about {
  animation-name: welcome-corner-in;
}

.welcome-entry {
  animation-name: welcome-corner-in;
}

@keyframes welcome-corner-in {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

@media (max-width: 900px) {
  .welcome-orbit {
    top: 42%;
    width: clamp(560px, 94vw, 820px);
  }

  .welcome-hero {
    top: 41%;
  }

  .welcome-hero__title {
    font-size: clamp(3.1rem, 10.5vw, 6.3rem);
  }

  .welcome-about {
    bottom: 34px;
    left: 30px;
    width: min(540px, calc(100vw - 310px));
  }

  .welcome-about p br {
    display: none;
  }

  .welcome-entry {
    right: 30px;
    bottom: 42px;
  }
}

@media (max-width: 680px) {
  .welcome-orbit {
    top: 35%;
    width: 128vw;
  }

  .welcome-hero {
    top: 35%;
    width: calc(100% - 34px);
  }

  .welcome-hero__title {
    gap: 12px;
    font-size: clamp(2.55rem, 13.5vw, 4.6rem);
  }

  .welcome-hero__tagline {
    margin-top: 18px;
    font-size: 0.92rem;
    letter-spacing: 0.08em;
  }

  .welcome-resume-zone {
    min-height: 82px;
    margin-top: 24px;
  }

  .welcome-resume {
    min-height: 48px;
  }

  .welcome-about {
    right: 24px;
    bottom: 122px;
    left: 24px;
    width: auto;
    padding: 14px 16px 14px 18px;
  }

  .welcome-about p {
    font-size: 0.9rem;
    line-height: 1.62;
  }

  .welcome-entry {
    right: 24px;
    bottom: 34px;
    left: 24px;
    width: calc(100% - 48px);
    min-height: 58px;
  }
}

@media (max-height: 700px) and (min-width: 681px) {
  .welcome-orbit {
    top: 44%;
    width: min(74vh, 760px);
  }

  .welcome-hero {
    top: 42%;
  }

  .welcome-about {
    bottom: 24px;
  }

  .welcome-entry {
    bottom: 30px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .welcome-orbit__layer,
  .welcome-orbit__core,
  .welcome-hero,
  .welcome-about,
  .welcome-entry {
    animation: none !important;
  }

  .welcome-entry,
  .welcome-entry__arrow,
  .welcome-resume,
  .welcome-resume__arrow,
  .welcome-notice-enter-active,
  .welcome-notice-leave-active {
    transition-duration: 1ms;
  }
}
</style>
