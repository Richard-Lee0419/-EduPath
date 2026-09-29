<template>
  <section class="async-page-loading" aria-live="polite" aria-label="功能页加载中">
    <div class="async-page-loading__header">
      <span></span>
      <strong>正在载入学习空间</strong>
      <small>仅首次打开需要加载</small>
    </div>
    <div class="async-page-loading__grid" aria-hidden="true">
      <i v-for="index in 6" :key="index"></i>
    </div>
  </section>
</template>

<style scoped>
.async-page-loading {
  min-height: calc(100vh - 72px);
  padding: 56px clamp(24px, 6vw, 90px);
  color: #315b55;
  background: linear-gradient(150deg, #f7faf4, #fff9ef);
}
.async-page-loading__header { display: grid; grid-template-columns: 12px auto; align-items: center; gap: 5px 12px; max-width: 1180px; margin: 0 auto 28px; }
.async-page-loading__header span { grid-row: 1 / 3; width: 10px; height: 10px; border-radius: 50%; background: #cf8422; box-shadow: 0 0 0 7px rgba(207,132,34,.13); animation: async-pulse 1.2s ease-in-out infinite; }
.async-page-loading__header strong { font-size: 18px; }
.async-page-loading__header small { color: #82928e; }
.async-page-loading__grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 18px; max-width: 1180px; margin: 0 auto; }
.async-page-loading__grid i { min-height: 150px; border: 1px solid rgba(25,92,82,.08); border-radius: 22px; background: linear-gradient(105deg, rgba(255,255,255,.9) 25%, rgba(232,242,237,.9) 45%, rgba(255,255,255,.9) 65%); background-size: 220% 100%; box-shadow: 0 18px 45px rgba(35,83,72,.06); animation: async-shimmer 1.4s linear infinite; }
@keyframes async-pulse { 50% { transform: scale(.72); opacity: .55; } }
@keyframes async-shimmer { to { background-position-x: -220%; } }
@media (max-width: 760px) { .async-page-loading__grid { grid-template-columns: 1fr; } .async-page-loading__grid i { min-height: 110px; } }
@media (prefers-reduced-motion: reduce) { .async-page-loading__header span, .async-page-loading__grid i { animation: none; } }
</style>
