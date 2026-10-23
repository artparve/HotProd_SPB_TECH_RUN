import Decimal from 'decimal.js'
import { catalog } from '../data/catalog'
import { compatible, geometry } from '../domain/room'
import { productInputSchema, projectSchema, roomSchema, roomLabels } from '../domain/types'
import type {
  Estimate,
  EstimateLine,
  Product,
  ProductInput,
  Project,
  Recommendations,
  Room,
} from '../domain/types'

const D = (value: Decimal.Value) => new Decimal(value)
const rounded = (value: Decimal) => value.toDecimalPlaces(4).toNumber()
export const versions = {
  catalog: 'demo-catalog-v1',
  rules: 'demo-rules-v1',
  model: 'none (rules only)',
}
export const demoWarning =
  'Демонстрационные товары, цены, нормы и тарифы. Это не коммерческая смета и не результат ML.'

export const demoCatalog = () => catalog

export function recommendDemo(room: Room): Recommendations {
  roomSchema.parse(room)
  const products = catalog.filter(
    (p) => p.works.some((work) => room.works.includes(work)) && compatible(p, room),
  )
  return {
    products: products.map((product) => ({
      product,
      reason: [
        `Подходит для: ${roomLabels[room.type].toLowerCase()}.`,
        room.humidity === 'high'
          ? 'Влагостойкость подтверждена в демо-карточке.'
          : 'Для заданных условий влажности.',
        room.load === 'high' ? 'Допускает повышенную нагрузку.' : '',
        !room.heated ? 'Допускает неотапливаемое помещение.' : '',
      ]
        .filter(Boolean)
        .join(' '),
    })),
    warnings: [
      demoWarning,
      'Неизвестная совместимость не считается подтверждённой. Неполные карточки исключены.',
    ],
  }
}

export function calculateLine(
  product: Product,
  input: ProductInput,
  roomId = 'product',
  roomName = 'Расчёт товара',
): EstimateLine {
  productInputSchema.parse(input)
  if (D(product.price).lte(0)) throw new Error('Цена не подтверждена. Уточните цену товара.')
  if (!product.packageSize) throw new Error('Нет подтверждённой фасовки товара.')
  const factor = D(1).plus(D(input.reserve).div(100))
  let need: Decimal
  let purchased: Decimal
  let packages: Decimal
  let formula: string
  if (product.kind === 'wallpaper') {
    if (!product.rollWidth || !product.rollLength || product.rapport === undefined)
      throw new Error('Нет размеров рулона или раппорта.')
    let strip = D(input.height).plus('0.1')
    if (product.rapport > 0) strip = strip.div(product.rapport).ceil().times(product.rapport)
    const perRoll = D(product.rollLength).div(strip).floor()
    if (perRoll.lt(1)) throw new Error('Высота полотна с подгоном превышает длину рулона.')
    need = D(input.perimeter).div(product.rollWidth).times(factor).ceil()
    packages = need.div(perRoll).ceil()
    purchased = packages.times(perRoll)
    formula = `ceil(${input.perimeter} м / ${product.rollWidth} м × ${factor}) = ${need} полотен; длина полотна с подгоном ${strip} м; ${perRoll} полотна/рул.; ceil(${need} / ${perRoll}) = ${packages} рул. Проёмы не вычитаются: консервативная раскладка.`
  } else {
    if (product.kind === 'covering') {
      need = D(input.area).times(factor)
      formula = `${input.area} м² × ${factor} = ${need} м²`
    } else if (['paint', 'primer', 'adhesive'].includes(product.kind)) {
      if (!product.consumption)
        throw new Error('Нет подтверждённой нормы расхода с единицами. Нужны данные производителя.')
      const layers = product.kind === 'paint' ? input.layers : 1
      need = D(input.area).times(product.consumption).times(layers).times(factor)
      formula = `${input.area} м² × ${product.consumption} ${product.unit}/м² × ${layers} сл. × ${factor} = ${need} ${product.unit}`
    } else {
      need = D(input.quantity).times(product.kind === 'cable' ? factor : 1)
      formula = `${input.quantity} ${product.unit}${product.kind === 'cable' ? ` × ${factor}` : ''} = ${need} ${product.unit}`
    }
    packages = need.div(product.packageSize).ceil()
    purchased = packages.times(product.packageSize)
    formula += `; ceil(${need} / ${product.packageSize}) = ${packages} ${product.saleUnit}`
  }
  return {
    id: `${roomId}:${product.id}`,
    roomId,
    roomName,
    product,
    quantity: packages.toNumber(),
    need: rounded(need),
    purchased: rounded(purchased),
    remainder: rounded(purchased.minus(need)),
    total: packages.times(product.price).toFixed(2),
    formula,
    reason: `Запас ${input.reserve}%: подрезка и потери (настройка пользователя). ${product.source}.`,
  }
}

function assemble(lines: EstimateLine[], labor = '0.00', warnings: string[] = []): Estimate {
  const materials = lines.reduce((sum, line) => sum.plus(line.total), D(0))
  return {
    id: crypto.randomUUID(),
    createdAt: new Date().toISOString(),
    mode: 'demo',
    lines,
    materials: materials.toFixed(2),
    labor,
    total: materials.plus(labor).toFixed(2),
    versions,
    warnings: [demoWarning, ...warnings],
  }
}

export function productDemo(product: Product, input: ProductInput): Estimate {
  return assemble([calculateLine(product, input)], '0.00', [
    'Работы не включены в расчёт отдельного товара.',
  ])
}

export function estimateDemo(project: Project, selections: Record<string, string> = {}): Estimate {
  projectSchema.parse(project)
  const lines: EstimateLine[] = []
  const warnings: string[] = [
    'Тарифы работ: пол 650 ₽/м², покраска 450 ₽/м², обои 400 ₽/м², розетка 900 ₽/точка, кабель 100 ₽/м. Все тарифы учебные.',
    'Тип объекта и класс ремонта передаются как контекст. В демо нет неподтверждённых наценок: состав определяется выбранными работами.',
    'Подготовка основания, демонтаж, доставка, гидроизоляция и инструменты не включены. Комплект предварительный.',
  ]
  let labor = D(0)
  for (const room of project.rooms) {
    const g = geometry(room)
    const candidates = recommendDemo(room).products.map((item) => item.product)
    const input: ProductInput = {
      area: g.area,
      perimeter: g.perimeter,
      height: room.height,
      reserve: room.reserve,
      layers: room.layers,
      quantity: 1,
    }
    const add = (kind: Product['kind'], area = g.area, quantity = 1) => {
      if (quantity <= 0) return undefined
      const selected = selections[`${room.id}:${kind}`]
      const product = candidates.find((p) => p.kind === kind && (!selected || p.id === selected))
      if (!product) {
        warnings.push(
          `${room.name}: нет подходящего товара «${kind}» с подтверждёнными условиями. Уточните материалы.`,
        )
        return undefined
      }
      lines.push(calculateLine(product, { ...input, area, quantity }, room.id, room.name))
      return product
    }
    if (room.works.includes('floor')) {
      const floor = add('covering')
      if (floor?.id === 'demo-tile') add('adhesive')
      labor = labor.plus(D(g.area).times(650))
    }
    if (room.works.includes('paint')) {
      add('paint', g.walls)
      labor = labor.plus(D(g.walls).times(450))
    }
    if (room.works.includes('wallpaper')) {
      add('wallpaper', g.walls)
      labor = labor.plus(D(g.walls).times(400))
    }
    if (room.works.includes('paint') || room.works.includes('wallpaper')) add('primer', g.walls)
    if (room.works.includes('electrical')) {
      add('socket', g.area, room.sockets)
      add('cable', g.area, room.cableLength)
      labor = labor.plus(D(room.sockets).times(900)).plus(D(room.cableLength).times(100))
      warnings.push(
        `${room.name}: электрика предварительная; трассы и точки заданы пользователем, нужен проект специалиста.`,
      )
    }
  }
  return assemble(lines, labor.toFixed(2), warnings)
}
