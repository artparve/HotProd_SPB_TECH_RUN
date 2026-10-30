import Decimal from 'decimal.js'
import type { Product, Room } from './types'

export function geometry(room: Room) {
  if (
    ![room.length, room.width, room.height, room.openings].every(
      (value) => typeof value === 'number' && Number.isFinite(value),
    )
  ) {
    return { area: NaN, perimeter: NaN, walls: NaN }
  }
  const perimeter = new Decimal(room.length).plus(room.width).times(2)
  return {
    area: new Decimal(room.length).times(room.width).toDecimalPlaces(4).toNumber(),
    perimeter: perimeter.toDecimalPlaces(4).toNumber(),
    walls: perimeter.times(room.height).minus(room.openings).toDecimalPlaces(4).toNumber(),
  }
}

export function compatible(product: Product, room: Room): boolean {
  return (
    (room.humidity !== 'high' || product.wet === true) &&
    (room.load !== 'high' || product.highLoad === true) &&
    (room.heated || product.unheated === true)
  )
}
