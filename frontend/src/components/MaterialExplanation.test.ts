import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { catalog } from '../data/catalog'
import type { MaterialEstimate } from '../domain/materials'
import { productDemo } from '../services/demo'
import MaterialExplanation from './MaterialExplanation.vue'

function response(): MaterialEstimate {
  const estimate = {
    ...productDemo(catalog[0]!, {
      area: 20,
      perimeter: 18,
      height: 2.7,
      reserve: 10,
      layers: 2,
      quantity: 6,
    }),
    mode: 'api' as const,
  }
  return {
    status: 'ok',
    estimate,
    baseline: estimate,
    correction: { applied: false, version: 'not-used', factor: '1' },
    model: 'assistant',
    toolCalls: ['calculate_material_cost'],
    explanation: '## Расчёт\n\n**Запас:** 10%\n\n- Проверьте размеры',
    explanationStatus: 'unverified_model_text',
  }
}

describe('MaterialExplanation', () => {
  it('renders variable JSON as labelled nested cards without trusting HTML or changing the estimate', () => {
    const value = response()
    value.explanation =
      '```json\n' +
      JSON.stringify({
        estimate: { total: '4980.00', lines: [{ product: { name: 'Краска' }, remainder: 4.72 }] },
        custom_key: ['<img src=x onerror=alert(1)>', true, null],
      }) +
      '\n```'
    const wrapper = mount(MaterialExplanation, { props: { response: value } })
    expect(wrapper.text()).toContain('Итого')
    expect(wrapper.text()).toContain('4980.00')
    expect(wrapper.text()).toContain('Остаток')
    expect(wrapper.text()).toContain('custom key')
    expect(wrapper.text()).toContain('Текст создан ИИ')
    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('pre').exists()).toBe(false)
  })
  it('formats the model explanation while retaining the accuracy warning', () => {
    const wrapper = mount(MaterialExplanation, { props: { response: response() } })
    expect(wrapper.get('.markdown-text h2').text()).toBe('Расчёт')
    expect(wrapper.get('.markdown-text strong').text()).toBe('Запас:')
    expect(wrapper.get('.markdown-text li').text()).toBe('Проверьте размеры')
    expect(wrapper.text()).toContain('может содержать ошибки')
    wrapper.unmount()
  })

  it('keeps a completed calculation and shows a plain safe error when explanation is unavailable', () => {
    const wrapper = mount(MaterialExplanation, {
      props: {
        response: {
          ...response(),
          explanation: null,
          explanationStatus: 'unavailable',
          explanationError: {
            code: 'upstream_unavailable',
            message: '<img src=x onerror=alert(1)>',
          },
        },
      },
    })
    expect(wrapper.find('.markdown-text').exists()).toBe(false)
    expect(wrapper.get('[role="status"]').text()).toContain('Смета рассчитана')
    expect(wrapper.find('img').exists()).toBe(false)
    wrapper.unmount()
  })
})
