import { describe, expect, it } from 'vitest'
import { catalog } from '../data/catalog'
import { calculateLine, estimateDemo, productDemo, recommendDemo } from '../services/demo'
import { compatible, geometry } from './room'
import { estimateSchema, newRoom, projectSchema, roomSchema } from './types'
import type { ProductInput } from './types'

const input: ProductInput = {
  area: 20,
  perimeter: 18,
  height: 2.7,
  reserve: 10,
  layers: 2,
  quantity: 6,
}
const product = (id: string) => catalog.find((p) => p.id === `demo-${id}`)!

describe('deterministic demo calculations', () => {
  it('applies 10% reserve to 20 m² and rounds up tile packages', () => {
    const line = calculateLine(product('tile'), input)
    expect(line.need).toBe(22)
    expect(line.quantity).toBe(16)
    expect(line.purchased).toBe(23.04)
    expect(line.remainder).toBe(1.04)
    expect(line.total).toBe('34560.00')
  })
  it('does not add a spurious package on a decimal boundary', () => {
    const line = calculateLine(
      { ...product('tile'), packageSize: 0.1, price: '0.10' },
      { ...input, area: 0.3, reserve: 0 },
    )
    expect(line.quantity).toBe(3)
    expect(line.total).toBe('0.30')
    expect(line.remainder).toBe(0)
  })
  it('accounts for paint layers once, unlike primer', () => {
    expect(calculateLine(product('paint'), input).need).toBe(5.28)
    expect(calculateLine(product('paint'), input).quantity).toBe(2)
    expect(calculateLine(product('primer'), input).need).toBe(2.2)
  })
  it('calculates whole wallpaper strips, rapport and rolls', () => {
    const line = calculateLine(product('wallpaper'), input)
    expect(line.need).toBe(19)
    expect(line.quantity).toBe(7)
    expect(line.purchased).toBe(21)
    expect(line.remainder).toBe(2)
    expect(line.formula).toContain('2.88 м')
    expect(line.formula).toContain('Проёмы не вычитаются')
  })
  it('handles zero rapport without dividing by zero', () => {
    const line = calculateLine({ ...product('wallpaper'), rapport: 0 }, input)
    expect(line.quantity).toBe(7)
  })
  it('rejects strips longer than a roll', () => {
    expect(() => calculateLine({ ...product('wallpaper'), rollLength: 2 }, input)).toThrow(
      'превышает',
    )
  })
  it('never invents missing consumption', () => {
    expect(() => calculateLine(product('unknown'), input)).toThrow('нормы расхода')
  })
  it('rejects unknown packaging and zero price', () => {
    expect(() => calculateLine({ ...product('tile'), packageSize: null }, input)).toThrow('фасовки')
    expect(() => calculateLine({ ...product('tile'), price: '0' }, input)).toThrow('Цена')
  })
  it('uses explicitly entered electrical quantities', () => {
    expect(calculateLine(product('socket'), input).quantity).toBe(6)
    expect(calculateLine(product('socket'), input).need).toBe(6)
    expect(calculateLine(product('cable'), { ...input, quantity: 70 }).quantity).toBe(2)
  })
  it('subtracts openings from actual walls, not from floor', () => {
    expect(geometry(newRoom())).toEqual({ area: 20, perimeter: 18, walls: 45.1 })
  })
  it('survives a temporarily blank input without crashing the UI', () => {
    expect(Number.isNaN(geometry({ ...newRoom(), length: '' as unknown as number }).area)).toBe(
      true,
    )
  })
  it('separates money totals and includes versions and limitations', () => {
    const estimate = estimateDemo({
      object: 'apartment',
      renovation: 'capital',
      rooms: [newRoom()],
    })
    expect(estimateSchema.safeParse(estimate).success).toBe(true)
    expect(estimate.materials).toBe('27060.00')
    expect(estimate.labor).toBe('33295.00')
    expect(estimate.total).toBe('60355.00')
    expect(estimate.versions.model).toContain('none')
    expect(estimate.warnings.join(' ')).toContain('Демонстрационные')
  })
  it('does not include labor in single-product calculations', () => {
    expect(productDemo(product('tile'), input).labor).toBe('0.00')
  })
  it('keeps multiple rooms separate', () => {
    const first = newRoom()
    const second = newRoom(2)
    const estimate = estimateDemo({
      object: 'house',
      renovation: 'cosmetic',
      rooms: [first, second],
    })
    expect(new Set(estimate.lines.map((l) => l.id)).size).toBe(estimate.lines.length)
    expect(estimate.lines.filter((l) => l.roomId === first.id)).toHaveLength(3)
    expect(estimate.total).toBe('120710.00')
  })
  it('recalculates when replacing a floor material', () => {
    const room = newRoom()
    const estimate = estimateDemo(
      { object: 'apartment', renovation: 'capital', rooms: [room] },
      { [`${room.id}:covering`]: 'demo-tile' },
    )
    expect(estimate.lines.some((line) => line.product.id === 'demo-glue')).toBe(true)
    expect(estimate.lines.find((line) => line.product.kind === 'covering')?.quantity).toBe(16)
  })
})

describe('validation and suitability', () => {
  it.each([0, -1, 101, NaN, Infinity])('rejects invalid length %s', (length) => {
    expect(roomSchema.safeParse({ ...newRoom(), length }).success).toBe(false)
  })
  it('rejects openings exceeding walls, no works and competing wall finishes', () => {
    expect(roomSchema.safeParse({ ...newRoom(), openings: 100 }).success).toBe(false)
    expect(roomSchema.safeParse({ ...newRoom(), works: [] }).success).toBe(false)
    expect(roomSchema.safeParse({ ...newRoom(), works: ['paint', 'wallpaper'] }).success).toBe(
      false,
    )
    expect(
      projectSchema.safeParse({ object: 'apartment', renovation: 'capital', rooms: [] }).success,
    ).toBe(false)
  })
  it('excludes known-incompatible and unknown moisture resistance', () => {
    const room = { ...newRoom(), humidity: 'high' as const }
    expect(compatible(product('laminate'), room)).toBe(false)
    expect(compatible(product('unknown'), room)).toBe(false)
    expect(compatible(product('tile'), room)).toBe(true)
    expect(recommendDemo(room).products.every((r) => r.product.wet === true)).toBe(true)
  })
  it('requires confirmed properties only for the conditions that use them', () => {
    const incomplete = { ...product('tile'), wet: null, highLoad: null, unheated: null }
    expect(compatible(incomplete, newRoom())).toBe(true)
    expect(compatible(incomplete, { ...newRoom(), humidity: 'high' })).toBe(false)
    expect(compatible(incomplete, { ...newRoom(), load: 'high' })).toBe(false)
    expect(compatible(incomplete, { ...newRoom(), heated: false })).toBe(false)
    expect(
      recommendDemo(newRoom()).products.some(({ product }) => product.id === 'demo-unknown'),
    ).toBe(false)
  })
  it('does not treat an unheated balcony as a normal living room', () => {
    const room = { ...newRoom(), type: 'balcony' as const, heated: false }
    expect(recommendDemo(room).products.some((r) => r.product.id === 'demo-laminate')).toBe(false)
  })
  it('reports unavailable wall coverings instead of silently substituting paint', () => {
    const room = { ...newRoom(), humidity: 'high' as const, works: ['wallpaper' as const] }
    const result = estimateDemo({ object: 'studio', renovation: 'designer', rooms: [room] })
    expect(result.warnings.join(' ')).toContain('нет подходящего товара')
    expect(result.lines.some((line) => line.product.kind === 'paint')).toBe(false)
  })
})
