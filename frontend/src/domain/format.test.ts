import { describe, expect, it } from 'vitest'
import { money } from './format'

describe('money formatting', () => {
  it.each([
    ['9007199254740993.01', '9 007 199 254 740 993,01 ₽'],
    ['99999999999999999.99', '99 999 999 999 999 999,99 ₽'],
    ['1234567890123456789012345.67', '1 234 567 890 123 456 789 012 345,67 ₽'],
    ['0.01', '0,01 ₽'],
    [2160, '2 160,00 ₽'],
  ])('preserves the exact amount %s', (amount, expected) => {
    expect(money(amount).replace(/\s/g, ' ')).toBe(expected)
  })
})
