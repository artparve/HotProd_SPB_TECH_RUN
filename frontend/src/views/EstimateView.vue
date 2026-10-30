<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import AppIcon from '../components/AppIcon.vue'
import RoomEditor from '../components/RoomEditor.vue'
import RoomPlan from '../components/RoomPlan.vue'
import ProductSwatch from '../components/ProductSwatch.vue'
import EstimateResult from '../components/EstimateResult.vue'
import RoomInspirationGallery from '../components/RoomInspirationGallery.vue'
import { workspace } from '../composables/useWorkspace'
import { useAsync } from '../composables/useAsync'
import { newRoom, projectSchema, roomLabels } from '../domain/types'
import type { Estimate, Product } from '../domain/types'
import { geometry, compatible } from '../domain/room'
import { service } from '../services/api'
import { money, number } from '../domain/format'
import { takeInspiration } from '../composables/useInspirationImport'
import { validateInspirationEstimate, type PreparedInspiration } from '../domain/inspirations'

const imported = takeInspiration()
// A standalone room calculation must not replace the user's multi-room project.
const project = imported
  ? reactive(projectSchema.parse(imported.result.project))
  : workspace.project
const step = ref(imported ? 2 : 1)
const activeRoom = ref(0)
const showValidation = ref(false)
const room = computed(() => project.rooms[activeRoom.value]!)
const { busy, error, run } = useAsync()
const products = ref<Product[]>(imported?.products || [])
const selections = ref<Record<string, string>>(imported?.result.selections || {})
const estimate = ref<Estimate | null>(imported?.estimate || null)
const inspiration = ref(
  imported ? { title: imported.image.title, warnings: imported.result.warnings } : null,
)
const steps = ['Параметры проекта', 'Выбор материалов', 'Готовая смета']
const totalArea = computed(() =>
  project.rooms.reduce((sum, item) => sum + (geometry(item).area || 0), 0),
)
const g = computed(() => geometry(room.value))
const inspirationProject = computed(() => ({
  object: project.object,
  renovation: project.renovation,
  rooms: [room.value],
}))
watch(
  project,
  () => {
    estimate.value = null
    selections.value = inspiration.value
      ? Object.fromEntries(
          Object.entries(selections.value).filter(([key, id]) => {
            const product = products.value.find((item) => item.id === id)
            const target = project.rooms.find((item) => key === `${item.id}:${product?.kind}`)
            return (
              product &&
              target &&
              compatible(product, target) &&
              product.works.some((work) => target.works.includes(work))
            )
          }),
        )
      : {}
    if (step.value === 3) step.value = 1
  },
  { deep: true },
)
function addRoom() {
  if (project.rooms.length >= 20) return
  project.rooms.push(newRoom(project.rooms.length + 1))
  activeRoom.value = project.rooms.length - 1
}
function removeRoom() {
  if (project.rooms.length <= 1) return
  project.rooms.splice(activeRoom.value, 1)
  activeRoom.value = Math.min(activeRoom.value, project.rooms.length - 1)
}
function choices(roomId: string, kind: Product['kind']) {
  const target = project.rooms.find((r) => r.id === roomId)!
  return products.value.filter((product) => product.kind === kind && compatible(product, target))
}
async function applyInspiration(value: PreparedInspiration) {
  await run(async () => {
    const target = room.value
    const selectedRoom = value.result.project.rooms[0]!
    if (target.id !== selectedRoom.id)
      throw new Error('Выбрана другая комната. Повторите подбор интерьера.')
    const fingerprint = JSON.stringify(project)
    const snapshot = projectSchema.parse(project)
    snapshot.rooms[activeRoom.value] = selectedRoom
    const nextSelections = {
      ...Object.fromEntries(
        Object.entries(selections.value).filter(
          ([key]) => key.slice(0, key.lastIndexOf(':')) !== target.id,
        ),
      ),
      ...value.result.selections,
    }
    const response =
      snapshot.rooms.length === 1
        ? value.estimate
        : await service.estimate(snapshot, nextSelections)
    validateInspirationEstimate(value.result, response)
    if (fingerprint !== JSON.stringify(project) || room.value.id !== target.id)
      throw new Error('Проект изменился. Повторите подбор интерьера для текущих параметров.')
    Object.assign(target, selectedRoom)
    // Let the ordinary project invalidation watcher finish before applying the new result.
    await nextTick()
    products.value = value.products
    selections.value = nextSelections
    estimate.value = response
    inspiration.value = { title: value.image.title, warnings: value.result.warnings }
    step.value = 2
  })
}
async function next() {
  showValidation.value = true
  if (!projectSchema.safeParse(project).success) {
    const invalid = project.rooms.findIndex(
      (item) => !projectSchema.shape.rooms.element.safeParse(item).success,
    )
    if (invalid >= 0) activeRoom.value = invalid
    step.value = 1
    return
  }
  await run(async () => {
    const fingerprint = JSON.stringify(project)
    const snapshot = projectSchema.parse(project)
    if (step.value === 1) {
      const catalog = await service.catalog()
      const response = await service.estimate(snapshot, selections.value)
      if (fingerprint !== JSON.stringify(project))
        throw new Error('Параметры изменились. Повторите подбор для новых размеров.')
      products.value = catalog
      estimate.value = response
      step.value = 2
    } else {
      const response = await service.estimate(snapshot, selections.value)
      if (fingerprint !== JSON.stringify(project))
        throw new Error('Параметры изменились. Повторите расчёт.')
      estimate.value = response
      step.value = 3
    }
  })
}
async function replace(key: string, event: Event) {
  const previous = selections.value[key]
  selections.value[key] = (event.target as HTMLSelectElement).value
  const fingerprint = JSON.stringify(project)
  const result = await run(async () => {
    const result = await service.estimate(projectSchema.parse(project), { ...selections.value })
    if (fingerprint !== JSON.stringify(project))
      throw new Error('Параметры изменились. Повторите подбор для новых размеров.')
    return result
  })
  if (result) estimate.value = result
  else if (previous) selections.value[key] = previous
  else delete selections.value[key]
}
</script>

<template>
  <div class="page-heading">
    <div>
      <span class="eyebrow">ПЛАНИРУЙТЕ С УВЕРЕННОСТЬЮ</span>
      <h1>Калькулятор ремонта<span class="heading-dot">.</span></h1>
      <p>От размеров комнаты до понятной сметы — за несколько шагов.</p>
    </div>
    <span class="heading-badge"><AppIcon name="sparkles" :size="16" /> Всё начинается с плана</span>
  </div>
  <section class="intro-banner">
    <div>
      <span class="intro-label"><span class="tiny-line" /> ВАШ РЕМОНТ, БЕЗ ЛИШНИХ ЗАТРАТ</span>
      <h2>Сначала посчитаем.<br />Потом — отремонтируем.</h2>
      <p>
        Подберём материалы под вашу задачу,<br class="desktop-only" />
        учтём запас и округлим до целых упаковок.
      </p>
      <span class="intro-feature"
        ><AppIcon name="check" :size="14" /> Материалы и работы — отдельно</span
      >
    </div>
    <RoomPlan :length="room.length" :width="room.width" /><span class="banner-corner"
      >01 / ПЛАНИРОВАНИЕ</span
    >
  </section>

  <section v-if="inspiration" class="alert info" aria-label="Подбор по изображению">
    <div>
      <strong>Материалы по интерьеру «{{ inspiration.title }}»</strong>
      <p>
        Размеры взяты из вашего проекта. Проверьте параметры и товары перед окончательным расчётом.
      </p>
      <ul>
        <li v-for="warning in inspiration.warnings" :key="warning">{{ warning }}</li>
      </ul>
    </div>
  </section>

  <ol class="stepper" aria-label="Этапы расчёта">
    <li
      v-for="(label, index) in steps"
      :key="label"
      :class="{ current: step === index + 1, complete: step > index + 1 }"
    >
      <button
        :disabled="index + 1 > step || busy"
        :aria-current="step === index + 1 ? 'step' : undefined"
        @click="step = index + 1"
      >
        <span class="step-number"
          ><AppIcon v-if="step > index + 1" name="check" :size="15" /><template v-else
            >0{{ index + 1 }}</template
          ></span
        ><span>{{ label }}</span></button
      ><span v-if="index < 2" class="step-line" />
    </li>
  </ol>

  <div class="workspace-grid">
    <section class="panel main-panel">
      <template v-if="step === 1">
        <div class="panel-heading">
          <div class="section-title">
            <span class="section-icon"><AppIcon name="home" /></span>
            <div>
              <h2>Расскажите о вашем проекте</h2>
              <p>Точные размеры — точный список материалов</p>
            </div>
          </div>
          <span class="subtle-number">01</span>
        </div>
        <form @submit.prevent="next">
          <div class="project-options">
            <fieldset class="field-group">
              <legend class="field-title">Тип объекта</legend>
              <div class="segmented">
                <button
                  v-for="(label, value) in {
                    apartment: 'Квартира',
                    house: 'Дом',
                    studio: 'Студия',
                  }"
                  :key="value"
                  type="button"
                  :class="{ selected: project.object === value }"
                  :aria-pressed="project.object === value"
                  @click="project.object = value"
                >
                  {{ label }}
                </button>
              </div>
            </fieldset>
            <label class="field"
              >Вариант ремонта<select v-model="project.renovation">
                <option value="cosmetic">Косметический</option>
                <option value="capital">Капитальный</option>
                <option value="designer">Дизайнерский</option>
              </select></label
            >
          </div>
          <div class="rooms-tabbar">
            <div role="group" aria-label="Помещения проекта">
              <button
                v-for="(item, index) in project.rooms"
                :key="item.id"
                type="button"
                :class="{ active: activeRoom === index }"
                :aria-pressed="activeRoom === index"
                @click="activeRoom = index"
              >
                <AppIcon :name="item.type === 'bathroom' ? 'bath' : 'door'" :size="16" />{{
                  item.name
                }}
              </button>
            </div>
            <button
              type="button"
              class="text-button"
              :disabled="project.rooms.length >= 20"
              @click="addRoom"
            >
              <AppIcon name="plus" :size="16" /> Добавить
            </button>
          </div>
          <div class="room-name-row">
            <label class="field"
              >Название помещения<input
                v-model="room.name"
                required
                maxlength="60"
                placeholder="Например, гостиная" /></label
            ><button
              v-if="project.rooms.length > 1"
              type="button"
              class="icon-button delete-room"
              aria-label="Удалить выбранную комнату"
              @click="removeRoom"
            >
              <AppIcon name="trash" :size="18" />
            </button>
          </div>
          <RoomEditor :key="room.id" :room="room" :show-validation="showValidation">
            <template #inspiration>
              <RoomInspirationGallery
                :project="inspirationProject"
                :disabled="busy"
                @prepared="applyInspiration"
              />
            </template>
          </RoomEditor>
          <div v-if="error" class="alert error" role="alert">{{ error }}</div>
          <div class="panel-bottom">
            <span class="muted"
              ><AppIcon name="shield" :size="16" /> Данные останутся в вашем браузере</span
            ><button class="button primary" type="submit" :disabled="busy">
              {{ busy ? 'Подбираем…' : 'Подобрать материалы' }}<AppIcon name="arrow" :size="18" />
            </button>
          </div>
        </form>
      </template>

      <template v-else-if="step === 2">
        <div class="panel-heading">
          <div class="section-title">
            <span class="section-icon"><AppIcon name="layers" /></span>
            <div>
              <h2>Материалы для вашего проекта</h2>
              <p>Проверьте подбор или выберите альтернативу</p>
            </div>
          </div>
          <span class="subtle-number">02</span>
        </div>
        <div class="alert info">
          <AppIcon name="info" :size="18" /><span>
            Проверьте материалы, количество и совместимость с условиями вашего проекта.
          </span>
        </div>
        <div v-for="line in estimate?.lines" :key="line.id" class="material-choice">
          <ProductSwatch :product="line.product" />
          <div class="material-info">
            <span class="line-category">{{ line.roomName }} · {{ line.product.category }}</span>
            <h3>{{ line.product.name }}</h3>
            <p>{{ line.product.description }}</p>
            <label v-if="choices(line.roomId, line.product.kind).length > 1" class="field"
              ><span class="sr-only">Альтернатива: {{ line.product.name }}</span
              ><select
                :value="selections[`${line.roomId}:${line.product.kind}`] || line.product.id"
                :disabled="busy"
                @change="replace(`${line.roomId}:${line.product.kind}`, $event)"
              >
                <option
                  v-for="product in choices(line.roomId, line.product.kind)"
                  :key="product.id"
                  :value="product.id"
                >
                  {{ product.name }} · {{ money(product.price) }}
                </option>
              </select></label
            ><span class="compatibility"
              ><AppIcon name="check" :size="13" /> Соответствует заданным условиям</span
            >
          </div>
          <strong class="choice-price"
            >{{ money(line.total)
            }}<small>{{ line.quantity }} {{ line.product.saleUnit }}</small></strong
          >
        </div>
        <div v-if="!estimate?.lines.length" class="empty-state">
          <AppIcon name="box" :size="35" />
          <h3>Подходящих материалов пока нет</h3>
          <p>Проверьте ограничения и состав каталога.</p>
        </div>
        <details v-if="estimate?.warnings.length" class="estimate-notes">
          <summary>
            <AppIcon name="warning" :size="17" /> Ограничения подбора
            <AppIcon name="down" :size="14" />
          </summary>
          <ul>
            <li v-for="warning in estimate.warnings" :key="warning">{{ warning }}</li>
          </ul>
        </details>
        <div v-if="error" class="alert error" role="alert">{{ error }}</div>
        <div class="panel-bottom">
          <button class="button ghost" @click="step = 1">
            <AppIcon name="back" :size="17" /> Параметры</button
          ><button class="button primary" :disabled="busy || !estimate?.lines.length" @click="next">
            {{ busy ? 'Рассчитываем…' : 'Рассчитать смету' }}<AppIcon name="arrow" :size="18" />
          </button>
        </div>
      </template>

      <template v-else-if="estimate"
        ><EstimateResult :estimate="estimate" />
        <div class="panel-bottom">
          <button class="button ghost" @click="step = 2">
            <AppIcon name="back" :size="17" /> Изменить материалы</button
          ><button class="text-button" @click="step = 1">Изменить параметры</button>
        </div></template
      >
    </section>

    <aside class="project-summary">
      <section class="panel summary-card">
        <div class="summary-title">
          <h2>Ваш проект</h2>
          <span class="tag">{{ project.rooms.length }} ПОМ.</span>
        </div>
        <div class="summary-property">
          <span class="summary-property-icon"><AppIcon name="home" :size="22" /></span>
          <div>
            <strong>{{
              { apartment: 'Квартира', house: 'Дом', studio: 'Студия' }[project.object]
            }}</strong
            ><small
              >{{
                { cosmetic: 'Косметический', capital: 'Капитальный', designer: 'Дизайнерский' }[
                  project.renovation
                ]
              }}
              ремонт</small
            >
          </div>
        </div>
        <div class="summary-area">
          <span>Общая площадь</span><strong>{{ number(totalArea) }}<small> м²</small></strong>
        </div>
        <div v-for="item in project.rooms" :key="item.id" class="summary-room">
          <span
            ><i />{{ item.name }}<small>{{ roomLabels[item.type] }}</small></span
          ><b>{{ number(geometry(item).area || 0) }} м²</b>
        </div>
        <div class="summary-divider" />
        <div class="summary-row">
          <span>Запас выбранной комнаты</span><b>{{ room.reserve }}%</b>
        </div>
        <div class="summary-row">
          <span>Площадь её стен</span><b>{{ g.walls > 0 ? number(g.walls) : '—' }} м²</b>
        </div>
        <template v-if="estimate"
          ><div class="summary-divider" />
          <div class="summary-row">
            <span>Материалы</span><b>{{ money(estimate.materials) }}</b>
          </div>
          <div class="summary-row">
            <span>Работы, ориентир</span><b>{{ money(estimate.labor) }}</b>
          </div>
          <div class="summary-total">
            <span>Предварительно</span><strong>{{ money(estimate.total) }}</strong>
          </div></template
        >
        <div v-else class="summary-placeholder">
          <AppIcon name="calculator" :size="22" />
          <p>Стоимость появится<br />после подбора материалов</p>
        </div>
      </section>
      <section class="good-to-know">
        <AppIcon name="bulb" :size="21" />
        <div>
          <h3>Зачем нужен запас?</h3>
          <p>
            Часть материала уйдёт на подрезку. Добавим {{ room.reserve }}%, затем округлим до
            упаковок — чтобы не пришлось докупать.
          </p>
          <span>Вы можете изменить запас в параметрах</span>
        </div>
      </section>
      <div class="summary-trust">
        <AppIcon name="shield" :size="18" /><span
          >Прозрачные формулы.<br />Никаких скрытых коэффициентов.</span
        >
      </div>
    </aside>
  </div>
  <div class="bottom-features">
    <span><AppIcon name="ruler" /> Учитываем ваши размеры</span
    ><span><AppIcon name="layers" /> Округляем до упаковок</span
    ><span><AppIcon name="file" /> Объясняем каждый расчёт</span>
  </div>
</template>
