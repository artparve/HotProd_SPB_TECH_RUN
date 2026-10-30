import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MarkdownText from './MarkdownText.vue'

describe('MarkdownText', () => {
  it('renders headings, emphasis, lists, tables, quotes and code', () => {
    const wrapper = mount(MarkdownText, {
      props: {
        text: [
          '## Расчёт материалов',
          '**Количество** и *запас*, ~~старое значение~~.',
          '- Первый пункт\n- Второй пункт',
          '1. Замер\n2. Покупка',
          '> Проверьте размеры',
          '| Материал | Количество |\n| --- | --- |\n| Краска | 2 банки |',
          '`ceil(need / packageSize)`',
          '```json\n{"total": "100.00"}\n```',
        ].join('\n\n'),
      },
    })
    expect(wrapper.get('h2').text()).toBe('Расчёт материалов')
    expect(wrapper.get('strong').text()).toBe('Количество')
    expect(wrapper.get('em').text()).toBe('запас')
    expect(wrapper.get('s').text()).toBe('старое значение')
    expect(wrapper.findAll('ul li')).toHaveLength(2)
    expect(wrapper.findAll('ol li')).toHaveLength(2)
    expect(wrapper.get('blockquote').text()).toBe('Проверьте размеры')
    expect(wrapper.get('table tbody td').text()).toBe('Краска')
    expect(wrapper.get('pre code').text()).toContain('"total"')
    wrapper.unmount()
  })

  it('keeps raw HTML, scripts and event handlers as text, not executable DOM', () => {
    const wrapper = mount(MarkdownText, {
      props: {
        text: '<script>alert(1)</script>\n<img src=x onerror="alert(1)">\n<iframe src="https://tracking.invalid"></iframe>\n<svg onload="alert(1)"></svg>',
      },
    })
    expect(wrapper.find('script, img, iframe, svg, [onerror], [onload]').exists()).toBe(false)
    expect(wrapper.text()).toContain('<script>alert(1)</script>')
    wrapper.unmount()
  })

  it.each([
    'javascript:alert(1)',
    'JaVaScRiPt:alert(1)',
    'jav&#x61;script:alert(1)',
    'data:text/html,hello',
    'vbscript:msgbox(1)',
    'file:///etc/passwd',
  ])('rejects unsafe links: %s', (href) => {
    const wrapper = mount(MarkdownText, { props: { text: `[Ссылка](${href})` } })
    expect(wrapper.find('a').exists()).toBe(false)
    wrapper.unmount()
  })

  it('secures ordinary links and never loads images from model output', () => {
    const wrapper = mount(MarkdownText, {
      props: {
        text: '[Источник](https://example.org/spec)\n\n![Схема](https://tracking.invalid/pixel.png)\n\n![<img src=x onerror=alert(1)>](/image.png)',
      },
    })
    expect(wrapper.get('a').attributes()).toMatchObject({
      href: 'https://example.org/spec',
      target: '_blank',
      rel: 'noopener noreferrer',
    })
    expect(wrapper.find('img, [onerror]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Схема')
    wrapper.unmount()
  })

  it('updates formatted output when the explanation changes', async () => {
    const wrapper = mount(MarkdownText, { props: { text: '**Старое**' } })
    await wrapper.setProps({ text: '*Новое*' })
    expect(wrapper.find('strong').exists()).toBe(false)
    expect(wrapper.get('em').text()).toBe('Новое')
    wrapper.unmount()
  })
})
