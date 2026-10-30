<script setup lang="ts">
import { computed } from 'vue'
import MarkdownIt from 'markdown-it'

const props = defineProps<{ text: string }>()
// Model output is untrusted: raw HTML and automatic external resources are disabled.
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false })
markdown.validateLink = (href) => {
  try {
    return ['http:', 'https:', 'mailto:'].includes(
      new URL(href, 'https://repaircalc.invalid').protocol,
    )
  } catch {
    return false
  }
}
markdown.renderer.rules.image = (tokens, index) =>
  markdown.utils.escapeHtml(tokens[index]?.content || '')
markdown.renderer.rules.link_open = (tokens, index, options, _env, renderer) => {
  const token = tokens[index]!
  token.attrSet('rel', 'noopener noreferrer')
  if (/^https?:\/\//i.test(String(token.attrGet('href') || ''))) token.attrSet('target', '_blank')
  return renderer.renderToken(tokens, index, options)
}
const html = computed(() => markdown.render(props.text))
</script>

<template>
  <!-- HTML is generated exclusively by the restricted Markdown renderer above. -->
  <div class="markdown-text" v-html="html" />
</template>

<style scoped lang="scss">
.markdown-text {
  min-width: 0;
  overflow-wrap: anywhere;
  line-height: 1.7;
  :deep(> :first-child) {
    margin-top: 0;
  }
  :deep(> :last-child) {
    margin-bottom: 0;
  }
  :deep(h1),
  :deep(h2),
  :deep(h3),
  :deep(h4),
  :deep(h5),
  :deep(h6) {
    margin: 1.2em 0 0.5em;
    font-size: 1.1em;
    line-height: 1.4;
  }
  :deep(ul),
  :deep(ol) {
    padding-left: 1.5em;
  }
  :deep(li) {
    margin: 0.3em 0;
  }
  :deep(blockquote) {
    margin: 1em 0;
    padding: 0.5em 1em;
    border-left: 3px solid #9eaf99;
    background: #f5f7f2;
  }
  :deep(pre) {
    max-width: 100%;
    overflow-x: auto;
    padding: 14px;
    border-radius: 8px;
    background: #f2f4ef;
    white-space: pre;
  }
  :deep(code) {
    font-family: ui-monospace, monospace;
    font-size: 0.9em;
  }
  :deep(:not(pre) > code) {
    background: #f2f4ef;
    padding: 0.15em 0.3em;
    border-radius: 4px;
  }
  :deep(table) {
    display: block;
    max-width: 100%;
    overflow-x: auto;
    border-collapse: collapse;
  }
  :deep(th),
  :deep(td) {
    padding: 8px 12px;
    border: 1px solid #dfe6d6;
    text-align: left;
  }
  :deep(a) {
    color: #285d49;
    text-decoration: underline;
  }
  :deep(hr) {
    margin: 1.2em 0;
    border: 0;
    border-top: 1px solid #dfe6d6;
  }
}
</style>
