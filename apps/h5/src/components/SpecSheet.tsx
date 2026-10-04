import { useEffect, useState } from 'react'
import { Icon } from './Icon'
import { SafeImg } from './SafeImg'
import { formatMoney } from '../lib/format'
import type { DishView } from '../api/types'

interface SpecSheetProps {
  dish: DishView
  submitting: boolean
  onClose: () => void
  onSubmit: (optionIds: string[], quantity: number) => void
}

/** 各规格组的最小加价之和：后端 DishView.price = 基础价 + 最小加价，用于反推基础价。 */
function minExtraOf(dish: DishView): number {
  return dish.specGroups.reduce((sum, group) => {
    const min = group.options.reduce((m, option) => Math.min(m, option.extraPrice), Number.POSITIVE_INFINITY)
    return sum + (Number.isFinite(min) ? min : 0)
  }, 0)
}

/** 规格选择弹层：单选组、默认选中 isDefault 项、加价实时计入、必选项未选不可提交。 */
export function SpecSheet({ dish, submitting, onClose, onSubmit }: SpecSheetProps) {
  const [selected, setSelected] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {}
    for (const group of dish.specGroups) {
      const preset = group.options.find((option) => option.isDefault)
      if (preset) initial[group.groupId] = preset.optionId
    }
    return initial
  })
  const [quantity, setQuantity] = useState(1)
  const [entered, setEntered] = useState(false)

  useEffect(() => {
    const frame = requestAnimationFrame(() => setEntered(true))
    return () => cancelAnimationFrame(frame)
  }, [])

  const basePrice = dish.price - minExtraOf(dish)
  let extra = 0
  const names: string[] = []
  const optionIds: string[] = []
  const selectedIds = new Set<string>()
  for (const group of dish.specGroups) {
    const option = group.options.find((item) => item.optionId === selected[group.groupId])
    if (option) {
      extra += option.extraPrice
      names.push(option.optionName)
      optionIds.push(option.optionId)
      selectedIds.add(group.groupId)
    }
  }
  const unitPrice = basePrice + extra
  const complete = dish.specGroups.every((group) => !group.isRequired || selectedIds.has(group.groupId))

  return (
    <>
      <div className={entered ? 'sheet-mask show' : 'sheet-mask'} onClick={submitting ? undefined : onClose} />
      <div className={entered ? 'sheet show' : 'sheet'} role="dialog" aria-modal="true" aria-label="选择规格">
        <div className="sheet-head">
          <div className="ss-prod">
            <SafeImg className="ss-img" src={dish.imageUrl} alt={dish.name} />
            <div className="ss-pinfo">
              <div className="ss-name">{dish.name}</div>
              <div className="ss-def">已选：{names.join(' / ') || '—'}</div>
              <div className="ss-price">
                <b className="price">¥{formatMoney(unitPrice)}</b>
                {dish.originPrice != null && dish.originPrice > dish.price && (
                  <span className="dish-orig">¥{formatMoney(dish.originPrice)}</span>
                )}
              </div>
            </div>
          </div>
          <button className="sheet-close" type="button" aria-label="关闭" onClick={onClose}>
            <Icon name="x" size={18} />
          </button>
        </div>

        <div className="sheet-body">
          {dish.specGroups.map((group) => (
            <div className="ss-group" key={group.groupId}>
              <div className="ss-gname">
                {group.groupName}
                {group.isRequired ? '（必选）' : ''}
              </div>
              <div className="opt-row">
                {group.options.map((option) => (
                  <button
                    className={selected[group.groupId] === option.optionId ? 'opt on' : 'opt'}
                    type="button"
                    key={option.optionId}
                    onClick={() => setSelected((prev) => ({ ...prev, [group.groupId]: option.optionId }))}
                  >
                    {option.optionName}
                    {option.extraPrice > 0 && <em> +¥{formatMoney(option.extraPrice)}</em>}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>

        <div className="sheet-foot">
          <div className="ss-qtyrow">
            <span>数量</span>
            <span className="stepper">
              <button
                className="minus"
                type="button"
                aria-label="减少"
                onClick={() => setQuantity((value) => Math.max(1, value - 1))}
              >
                <Icon name="minus" size={14} />
              </button>
              <span className="num">{quantity}</span>
              <button
                className="plus"
                type="button"
                aria-label="增加"
                onClick={() => setQuantity((value) => Math.min(99, value + 1))}
              >
                <Icon name="plus" size={14} />
              </button>
            </span>
          </div>
          <button
            className="btn btn-grad ss-submit"
            type="button"
            disabled={!complete || submitting}
            onClick={() => onSubmit(optionIds, quantity)}
          >
            {complete ? `选好了 · ¥${formatMoney(unitPrice * quantity)}` : '请选择规格'}
          </button>
        </div>
      </div>
    </>
  )
}
