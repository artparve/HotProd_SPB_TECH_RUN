import { describe, expect, it } from 'vitest'
import { explanationJson, explanationLabel } from './explanation'

describe('model explanation JSON', () => {
  it('accepts arbitrary nested objects, arrays and fenced JSON', () => {
    expect(explanationJson('```json\n{"custom":[true,null,{"foo":2}]}\n```')).toEqual({
      custom: [true, null, { foo: 2 }],
    })
    expect(explanationJson('[1,"two"]')).toEqual([1, 'two'])
    expect(explanationLabel('total')).toBe('Итого')
    expect(explanationLabel('custom_key')).toBe('custom key')
  })
  it('leaves Markdown, malformed and oversized JSON as text without evaluation', () => {
    for (const text of ['## Пояснение', '{oops}', 'null', '{"x":"' + 'a'.repeat(100001) + '"}'])
      expect(explanationJson(text)).toBeUndefined()
  })
})
