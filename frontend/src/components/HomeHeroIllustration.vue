<template>
  <div class="home-hero-robot">
    <span class="home-hero-robot__glow" aria-hidden="true"></span>
    <svg
      class="home-hero-illustration home-hero-illustration--robot home-hero-robot-svg"
      viewBox="0 0 420 360"
      role="img"
      aria-labelledby="homeRobotTitle homeRobotDescription"
    >
      <title id="homeRobotTitle">知径 AI 机器人动态助手</title>
      <desc id="homeRobotDescription">一个正在悬浮并联动对话、提醒、设置与工具能力的智能机器人</desc>

      <defs>
        <linearGradient id="robotShell" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#c86cf6" />
          <stop offset="0.36" stop-color="#9878f2" />
          <stop offset="0.7" stop-color="#79d7f2" />
          <stop offset="1" stop-color="#b8f0f2" />
        </linearGradient>
        <linearGradient id="robotShellSoft" x1="0" y1="0" x2="0.9" y2="1">
          <stop offset="0" stop-color="#dc73f6" />
          <stop offset="0.5" stop-color="#aa71ef" />
          <stop offset="1" stop-color="#72d5ed" />
        </linearGradient>
        <linearGradient id="robotFace" x1="0" y1="0" x2="0.8" y2="1">
          <stop offset="0" stop-color="#123c83" />
          <stop offset="0.55" stop-color="#075c92" />
          <stop offset="1" stop-color="#0788a5" />
        </linearGradient>
        <linearGradient id="robotBubble" x1="0.2" y1="0" x2="0.8" y2="1">
          <stop offset="0" stop-color="#15243f" />
          <stop offset="0.54" stop-color="#102c53" />
          <stop offset="1" stop-color="#086e9b" />
        </linearGradient>
        <linearGradient id="robotBody" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#e276ef" />
          <stop offset="0.48" stop-color="#b86de8" />
          <stop offset="1" stop-color="#7a73ec" />
        </linearGradient>
        <radialGradient id="robotHighlight" cx="0.28" cy="0.18" r="0.9">
          <stop offset="0" stop-color="#ffffff" stop-opacity="0.9" />
          <stop offset="0.26" stop-color="#ffffff" stop-opacity="0.26" />
          <stop offset="1" stop-color="#ffffff" stop-opacity="0" />
        </radialGradient>
        <filter id="robotSoftShadow" x="-60%" y="-60%" width="220%" height="240%">
          <feDropShadow dx="0" dy="12" stdDeviation="12" flood-color="#44588c" flood-opacity="0.2" />
        </filter>
        <filter id="robotBubbleShadow" x="-70%" y="-70%" width="240%" height="260%">
          <feDropShadow dx="0" dy="8" stdDeviation="8" flood-color="#1e4362" flood-opacity="0.2" />
        </filter>
      </defs>

      <ellipse class="robot-ground-shadow" cx="210" cy="324" rx="76" ry="12" />

      <g class="robot-status robot-status--chat" filter="url(#robotBubbleShadow)">
        <circle cx="72" cy="69" r="37" fill="url(#robotBubble)" />
        <circle cx="72" cy="69" r="35.5" fill="none" stroke="#b5ecf3" stroke-opacity="0.3" />
        <path d="M52 55h40a8 8 0 0 1 8 8v12a8 8 0 0 1-8 8H74L62 92v-9H52a8 8 0 0 1-8-8V63a8 8 0 0 1 8-8Z" fill="#fff" fill-opacity="0.95" />
        <circle cx="60" cy="69" r="3.4" fill="#16476f" />
        <circle cx="72" cy="69" r="3.4" fill="#16476f" />
        <circle cx="84" cy="69" r="3.4" fill="#16476f" />
      </g>

      <g class="robot-status robot-status--alert" filter="url(#robotBubbleShadow)">
        <circle cx="349" cy="78" r="34" fill="url(#robotBubble)" />
        <circle cx="349" cy="78" r="32.5" fill="none" stroke="#b5ecf3" stroke-opacity="0.3" />
        <path d="M349 57v25" fill="none" stroke="#fff" stroke-width="7" stroke-linecap="round" />
        <circle cx="349" cy="93" r="4.5" fill="#fff" />
      </g>

      <g class="robot-status robot-status--settings" filter="url(#robotBubbleShadow)">
        <circle cx="70" cy="277" r="38" fill="url(#robotBubble)" />
        <circle cx="70" cy="277" r="36.5" fill="none" stroke="#b5ecf3" stroke-opacity="0.3" />
        <g fill="none" stroke="#fff" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="70" cy="277" r="12" stroke-width="6" />
          <path d="M70 251v9M70 294v9M44 277h9M87 277h9M52 259l7 7M81 288l7 7M88 259l-7 7M59 288l-7 7" stroke-width="7" />
        </g>
      </g>

      <g class="robot-status robot-status--tools" filter="url(#robotBubbleShadow)">
        <circle cx="350" cy="275" r="38" fill="url(#robotBubble)" />
        <circle cx="350" cy="275" r="36.5" fill="none" stroke="#b5ecf3" stroke-opacity="0.3" />
        <path
          d="M333 252c7-5 16-4 22 2l-10 10 9 9 10-10c4 8 1 18-7 22-5 2-10 2-14 0l-13 13c-3 3-8 3-11 0s-3-8 0-11l13-13c-4-7-3-16 1-22Z"
          fill="#fff"
        />
      </g>

      <g class="robot-core" filter="url(#robotSoftShadow)">
        <g class="robot-arm robot-arm--left">
          <path d="M157 226c-18 2-31 12-42 28" fill="none" stroke="url(#robotShellSoft)" stroke-width="25" stroke-linecap="round" />
          <circle cx="112" cy="258" r="11" fill="#92ddeb" />
        </g>
        <g class="robot-arm robot-arm--right">
          <path d="M263 226c18 2 31 12 42 28" fill="none" stroke="url(#robotShellSoft)" stroke-width="25" stroke-linecap="round" />
          <circle cx="308" cy="258" r="11" fill="#92ddeb" />
        </g>

        <g class="robot-torso">
          <path d="M173 222c0-22 17-39 37-39s37 17 37 39v47c0 24-16 43-37 43s-37-19-37-43v-47Z" fill="url(#robotBody)" />
          <path d="M181 218c4-16 15-25 29-25 15 0 26 9 30 25" fill="none" stroke="#f6d2ff" stroke-opacity="0.38" stroke-width="6" stroke-linecap="round" />
          <ellipse cx="195" cy="218" rx="12" ry="18" fill="url(#robotHighlight)" opacity="0.72" />
          <path d="M198 312h24l-4 24a8 8 0 0 1-16 0l-4-24Z" fill="#5f2b8c" />
          <ellipse cx="210" cy="312" rx="16" ry="7" fill="#8f46bd" />
        </g>

        <g class="robot-head">
          <circle cx="137" cy="142" r="18" fill="#6fcce8" />
          <circle cx="283" cy="142" r="18" fill="#6fcce8" />
          <rect x="128" y="82" width="164" height="124" rx="62" fill="url(#robotShell)" />
          <rect x="132" y="86" width="156" height="116" rx="58" fill="none" stroke="#fff" stroke-opacity="0.34" stroke-width="3" />
          <ellipse cx="174" cy="110" rx="35" ry="25" fill="url(#robotHighlight)" opacity="0.7" />
          <rect x="151" y="106" width="118" height="77" rx="36" fill="url(#robotFace)" />
          <rect x="154" y="109" width="112" height="71" rx="33" fill="none" stroke="#89e7ee" stroke-opacity="0.22" stroke-width="2" />

          <g class="robot-eyes">
            <rect class="robot-eye robot-eye--left" x="176" y="130" width="13" height="24" rx="7" fill="#fff" />
            <rect class="robot-eye robot-eye--right" x="231" y="130" width="13" height="24" rx="7" fill="#fff" />
          </g>
          <path d="M198 158c7 8 17 8 24 0" fill="none" stroke="#fff" stroke-width="7" stroke-linecap="round" />

          <path class="robot-headset" d="M127 150c2 36 35 60 83 60s81-24 83-60" fill="none" stroke="#e9fbff" stroke-opacity="0.9" stroke-width="7" stroke-linecap="round" />
          <path d="M279 179c12 0 20-7 20-18" fill="none" stroke="#e9fbff" stroke-width="6" stroke-linecap="round" />
          <circle cx="299" cy="159" r="6" fill="#fff" />

          <path d="M257 89c8 1 14 5 18 12" fill="none" stroke="#fff" stroke-opacity="0.72" stroke-width="6" stroke-linecap="round" />
          <circle cx="276" cy="104" r="4" fill="#fff" fill-opacity="0.72" />
        </g>
      </g>
    </svg>
  </div>
</template>
