<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { getReviewLinks, issueReviewLink, issueTeacherInvite, revokeReviewLink } from '../api/accountAccess';
import type { IssuedReviewLink, ReviewLinks, TeacherInvite } from '../api/accountAccess';

const inviteEmail = ref('');
const inviteHours = ref(72);
const reviewHours = ref(72);
const invite = ref<TeacherInvite | null>(null);
const issuedLink = ref<IssuedReviewLink | null>(null);
const review = ref<ReviewLinks | null>(null);
const error = ref('');
const status = ref('');
const busy = ref(false);
const timeFormat = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'short' });

const linkUrl = computed(() => issuedLink.value
  ? new URL('/login#review=' + issuedLink.value.code, location.origin).href
  : '');

const formatExpiry = (value: string) => timeFormat.format(new Date(value));

const perform = async (action: () => Promise<void>) => {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  status.value = '';
  try {
    await action();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '操作失败，请稍后重试';
  } finally {
    busy.value = false;
  }
};

const createInvite = () => perform(async () => {
  invite.value = await issueTeacherInvite(inviteHours.value, inviteEmail.value.trim() || undefined);
});

const createLink = () => perform(async () => {
  const link = await issueReviewLink(reviewHours.value);
  issuedLink.value = link;
  const { code: _code, ...metadata } = link;
  review.value = { enabled: true, links: [{ ...metadata, active: true }, ...(review.value?.links ?? [])].slice(0, 25) };
});

const removeLink = (id: number) => perform(async () => {
  await revokeReviewLink(id);
  const link = review.value?.links.find((item) => item.id === id);
  if (link) link.active = false;
  if (issuedLink.value?.id === id) issuedLink.value = null;
  status.value = '评委链接已撤销';
});

const copy = async (value: string) => {
  try {
    await navigator.clipboard.writeText(value);
    status.value = '已复制';
  } catch {
    status.value = '请选中文本后手动复制';
  }
};

onMounted(() => perform(async () => { review.value = await getReviewLinks(); }));
</script>

<template>
  <section class="account-access" aria-labelledby="account-access-heading">
    <h2 id="account-access-heading">教师邀请码与评委链接</h2>
    <div class="account-access__grid">
      <article>
        <h3>教师邀请码</h3>
        <form @submit.prevent="createInvite">
          <label>
            教师邮箱（可选）
            <input v-model="inviteEmail" type="email" autocomplete="off" placeholder="绑定后仅该邮箱可兑换" />
          </label>
          <label>
            有效期（小时）
            <input v-model.number="inviteHours" type="number" min="1" max="720" required />
          </label>
          <button type="submit" class="primary-action" :disabled="busy">生成一次性邀请码</button>
        </form>
        <div v-if="invite" class="account-access__result">
          <label>教师邀请码<input :value="invite.code" readonly /></label>
          <p>有效至 {{ formatExpiry(invite.expires_at) }}</p>
          <button type="button" class="text-action" @click="copy(invite!.code)">复制邀请码</button>
        </div>
      </article>

      <article>
        <h3>评委专属链接</h3>
        <p v-if="review && !review.enabled">在独立演示环境开启评委入口后，可以在这里生成链接。</p>
        <form v-if="review?.enabled" @submit.prevent="createLink">
          <label>
            有效期（小时）
            <input v-model.number="reviewHours" type="number" min="1" max="720" required />
          </label>
          <button type="submit" class="primary-action" :disabled="busy">生成评委链接</button>
        </form>
        <div v-if="issuedLink" class="account-access__result">
          <label>评委专属链接<input :value="linkUrl" readonly /></label>
          <p>有效至 {{ formatExpiry(issuedLink.expires_at) }}</p>
          <button type="button" class="text-action" @click="copy(linkUrl)">复制评委链接</button>
        </div>
        <ul v-if="review?.links.length" class="account-access__links" aria-label="近期签发的评委链接">
          <li v-for="link in review.links" :key="link.id">
            <span>{{ link.class_name }} <small>{{ link.active ? formatExpiry(link.expires_at) + ' 到期' : '已失效' }}</small></span>
            <button v-if="link.active" type="button" class="text-action" :disabled="busy" @click="removeLink(link.id)">撤销链接</button>
          </li>
        </ul>
      </article>
    </div>
    <p v-if="error" class="auth-error" role="alert">{{ error }}</p>
    <p v-if="status" role="status">{{ status }}</p>
  </section>
</template>

<style scoped>
.account-access {
  border: 1px solid var(--line);
  border-radius: var(--radius-panel);
  background: var(--surface);
  padding: clamp(16px, 3vw, 26px);
}

.account-access h2 { margin: 0 0 16px; font-size: 1.2rem; }
.account-access h3 { margin: 0 0 12px; color: var(--color-primary-deep); font-size: 1rem; }
.account-access p { margin: 8px 0; color: var(--muted); font-size: 0.88rem; }

.account-access__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 250px), 1fr));
  gap: 20px;
}

.account-access article {
  min-width: 0;
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 14px;
}

.account-access form,
.account-access label,
.account-access__result { display: grid; gap: 7px; }
.account-access form { gap: 10px; }

.account-access label {
  font-size: 0.84rem;
  color: var(--ink);
}

.account-access input {
  min-width: 0;
  width: 100%;
  padding: 9px;
  border: 1px solid var(--line);
  border-radius: var(--radius-control);
  color: var(--ink);
  background: var(--surface-strong);
}

.account-access .primary-action,
.account-access .text-action { justify-self: start; min-height: 36px; padding: 6px 10px; }

.account-access__result {
  margin-top: 12px;
  border-top: 1px solid var(--line);
  padding-top: 12px;
}

.account-access__links { list-style: none; margin: 14px 0 0; padding: 0; }

.account-access__links li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-top: 1px solid var(--line);
  font-size: 0.86rem;
}

.account-access__links small { display: block; color: var(--muted); }
.account-access__links .text-action { flex-shrink: 0; }
</style>
