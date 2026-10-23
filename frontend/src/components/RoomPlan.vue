<script setup lang="ts">
// v-model.number temporarily emits an empty string while a field is cleared.
defineProps<{ length?: number | string; width?: number | string; compact?: boolean }>()
</script>

<template>
  <svg
    class="room-plan"
    :class="{ compact }"
    viewBox="0 0 330 230"
    fill="none"
    role="img"
    aria-label="Схематичный план комнаты, не в масштабе"
  >
    <defs>
      <pattern id="plan-grid" width="18" height="18" patternUnits="userSpaceOnUse">
        <path d="M18 0H0V18" stroke="currentColor" stroke-width=".4" opacity=".16" />
      </pattern>
    </defs>
    <rect width="330" height="230" fill="url(#plan-grid)" />
    <rect x="55" y="48" width="217" height="146" fill="#f4f7ee" />
    <path d="M55 48H272V194H192M144 194H55V48" fill="none" stroke="currentColor" stroke-width="5" />
    <path
      d="M145 194v-46a47 47 0 0 1 47 46M192 194h-47"
      stroke="currentColor"
      stroke-width="1.2"
      opacity=".6"
    />
    <path d="M55 69v63m217-62v63" stroke="#e1ead9" stroke-width="7" />
    <path d="M52 69h6v63h-6Zm217 1h6v63h-6Z" stroke="currentColor" stroke-width="1.2" />
    <rect
      x="79"
      y="66"
      width="78"
      height="41"
      rx="4"
      fill="#d9e5cd"
      stroke="currentColor"
      stroke-width="1.4"
    />
    <rect x="85" y="72" width="12" height="29" rx="3" stroke="currentColor" opacity=".5" />
    <path d="M101 66v41" stroke="currentColor" opacity=".5" />
    <rect x="219" y="144" width="33" height="32" rx="3" stroke="currentColor" fill="#e5ecd9" />
    <circle cx="235" cy="160" r="9" stroke="currentColor" />
    <path d="M235 150v20m-10-10h20" stroke="currentColor" opacity=".4" />
    <rect x="173" y="83" width="33" height="52" rx="16" stroke="currentColor" opacity=".5" />
    <path
      d="M55 27h217M55 22v10m217-10v10M295 48v146m-5-146h10m-10 146h10"
      stroke="currentColor"
      stroke-width="1"
      opacity=".6"
    />
    <rect x="135" y="17" width="58" height="20" fill="#edf3e7" />
    <text
      x="164"
      y="31"
      fill="currentColor"
      text-anchor="middle"
      font-size="12"
      font-family="sans-serif"
    >
      {{ length || '—' }} м
    </text>
    <rect x="283" y="109" width="35" height="20" fill="#edf3e7" />
    <text
      x="300"
      y="123"
      fill="currentColor"
      text-anchor="middle"
      font-size="12"
      font-family="sans-serif"
    >
      {{ width || '—' }} м
    </text>
    <circle cx="87" cy="157" r="13" fill="#d9e5cd" />
    <path d="m79 160 8-15 5 15-11-7 13 1" stroke="currentColor" stroke-width="1" opacity=".7" />
    <text x="156" y="216" fill="currentColor" font-size="9" letter-spacing="2" opacity=".65">
      ПЛАН ВАШЕГО ПРОЕКТА
    </text>
  </svg>
</template>
