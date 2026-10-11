import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { StatusBar } from '../components/StatusBar'
import { TabBar } from '../components/TabBar'
import { Icon } from '../components/Icon'
import {
  PREFERENCE_QUERY_KEY,
  createPreference,
  deletePreference,
  fetchPreferences,
  preferenceLabel,
} from '../api/preferences'
import type { PreferenceType } from '../api/preferences'
import { logoutSession } from '../api/session'
import { useSessionStore } from '../store/session'
import { showToast } from '../store/ui'
import { PLACEHOLDER_TOAST } from '../lib/constants'
import './Mine.css'

interface PrefOption {
  type: PreferenceType
  content: string
  label: string
}

/** 弹层分组选项：内容与 Agent 词表对齐（不辣/微辣/中辣、一人食/多人分享均在闭集内）。 */
const PREF_GROUPS: { title: string; options: PrefOption[] }[] = [
  {
    title: '口味',
    options: [
      { type: 'TASTE', content: '微辣', label: '微辣' },
      { type: 'TASTE', content: '中辣', label: '中辣' },
      { type: 'TASTE', content: '不辣', label: '不辣' },
    ],
  },
  {
    title: '预算',
    options: [
      { type: 'BUDGET', content: '20', label: '≤ ¥20' },
      { type: 'BUDGET', content: '30', label: '≤ ¥30' },
      { type: 'BUDGET', content: '50', label: '≤ ¥50' },
    ],
  },
  {
    title: '忌口',
    options: [
      { type: 'AVOID', content: '香菜', label: '不吃香菜' },
      { type: 'AVOID', content: '花生', label: '不吃花生' },
      { type: 'AVOID', content: '葱', label: '不要葱' },
    ],
  },
  {
    title: '场景',
    options: [
      { type: 'SCENE', content: '一人食', label: '一人食' },
      { type: 'SCENE', content: '多人分享', label: '多人分享' },
    ],
  },
]

const PREF_OPTIONS = PREF_GROUPS.flatMap((group) => group.options)

function optionKey(option: PrefOption): string {
  return `${option.type}:${option.content}`
}

/** 「我的」页（Shell 变体 A）：身份 / 偏好记忆卡 / 评测入口 / 退出登录。 */
export function Mine() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const nickname = useSessionStore((state) => state.nickname)
  const loginAt = useSessionStore((state) => state.loginAt)
  const [sheetOpen, setSheetOpen] = useState(false)
  const [picked, setPicked] = useState<string[]>([])

  const prefsQuery = useQuery({ queryKey: PREFERENCE_QUERY_KEY, queryFn: fetchPreferences })
  const items = prefsQuery.data?.items ?? []

  const addMutation = useMutation({
    mutationFn: async () => {
      const options = PREF_OPTIONS.filter((option) => picked.includes(optionKey(option)))
      for (const option of options) {
        await createPreference(option.type, option.content)
      }
    },
    onSuccess: () => {
      setSheetOpen(false)
      setPicked([])
      void queryClient.invalidateQueries({ queryKey: PREFERENCE_QUERY_KEY })
      showToast('已记住新的偏好')
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '保存失败，请重试'),
  })

  const removeMutation = useMutation({
    mutationFn: deletePreference,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: PREFERENCE_QUERY_KEY })
      showToast('已忘记这条偏好')
    },
    onError: (error) => showToast(error instanceof Error ? error.message : '删除失败，请重试'),
  })

  const logoutMutation = useMutation({
    mutationFn: logoutSession,
    onSettled: () => {
      // 退出即清空本地会话与缓存，回到封面页重新建立身份
      useSessionStore.getState().clear()
      queryClient.clear()
      navigate('/', { replace: true })
    },
  })

  const toggle = (option: PrefOption) => {
    const key = optionKey(option)
    setPicked((prev) => (prev.includes(key) ? prev.filter((item) => item !== key) : [...prev, key]))
  }

  return (
    <div className="app app--mine">
      <StatusBar warm />

      {/* W4 动效：stagger 提升到 screen 层，头部→偏好卡→入口→退出依次 0.02–0.18s 入场 */}
      <main className="screen mine-screen stagger">
        <div className="mine-top">
          <div className="profile">
            <span className="avatar">
              <Icon name="user" size={26} />
              <i className="avatar-spark">
                <Icon name="sparkles" size={12} />
              </i>
            </span>
            <div className="profile-main">
              <div className="profile-name">{nickname || '未登录'}</div>
              <div className="profile-sub">{profileSubtitle(loginAt)}</div>
            </div>
          </div>
        </div>

        <div className="card prefs-card">
          <div className="card-head">
            <div className="card-title">口味偏好 · 我帮你记住</div>
            <div className="card-sub">会话开场自动带入，可随时调整</div>
          </div>
          <div className="prefs-chips">
            {prefsQuery.isPending && <span className="prefs-empty">加载中…</span>}
            {prefsQuery.isError && (
              <span className="prefs-empty">
                偏好加载失败
                <button className="prefs-retry" type="button" onClick={() => prefsQuery.refetch()}>
                  重试
                </button>
              </span>
            )}
            {prefsQuery.isSuccess && items.length === 0 && (
              <span className="prefs-empty">还没有偏好，点下方添加</span>
            )}
            {items.map((item) => (
              <button
                className="chip pref-chip"
                type="button"
                key={item.id}
                aria-label={`删除偏好 ${preferenceLabel(item)}`}
                disabled={removeMutation.isPending}
                onClick={() => removeMutation.mutate(item.id)}
              >
                <span>{preferenceLabel(item)}</span>
                <Icon name="x" size={12} />
              </button>
            ))}
          </div>
          <button className="add-pref" type="button" onClick={() => setSheetOpen(true)}>
            <Icon name="plus" size={14} />
            添加偏好
          </button>
        </div>

        <div className="card entry-card">
          <Link className="entry-row" to="/eval">
            <span className="entry-ico entry-ico--primary">
              <Icon name="circle-check" size={19} />
            </span>
            <span className="entry-main">
              <span className="entry-title">评测看板</span>
              <span className="entry-sub">Golden 用例 · 校准曲线 · 延迟与成本</span>
            </span>
            <i className="tag tag-gray">演示</i>
            <span className="entry-chev">
              <Icon name="chevron-right" size={18} />
            </span>
          </Link>
          <button className="entry-row" type="button" onClick={() => showToast(PLACEHOLDER_TOAST)}>
            <span className="entry-ico">
              <Icon name="sparkles" size={19} />
            </span>
            <span className="entry-main">
              <span className="entry-title">关于 MealMind</span>
              <span className="entry-sub">v0.1 · 模拟数据</span>
            </span>
            <span className="entry-chev">
              <Icon name="chevron-right" size={18} />
            </span>
          </button>
        </div>

        <div className="logout-wrap">
          <button
            className="btn btn-outline btn-lg"
            type="button"
            disabled={logoutMutation.isPending}
            onClick={() => logoutMutation.mutate()}
          >
            {logoutMutation.isPending ? '退出中…' : '退出登录'}
          </button>
        </div>

        <p className="app-footer-note">模拟数据 · 仅用于产品演示</p>
      </main>

      <TabBar active="mine" />

      <div
        className={sheetOpen ? 'sheet-mask show' : 'sheet-mask'}
        onClick={() => setSheetOpen(false)}
      />
      <div
        className={sheetOpen ? 'sheet show' : 'sheet'}
        role="dialog"
        aria-label="添加偏好"
        aria-hidden={!sheetOpen}
      >
        <div className="sheet-head">
          <div>
            <div className="sheet-title">添加偏好</div>
            <div className="sheet-sub">可多选，保存后会话开场自动带入</div>
          </div>
          <button className="sheet-close" type="button" aria-label="关闭" onClick={() => setSheetOpen(false)}>
            <Icon name="x" size={18} />
          </button>
        </div>
        <div className="sheet-body">
          {PREF_GROUPS.map((group) => (
            <div className="pref-group" key={group.title}>
              <div className="pref-group-t">{group.title}</div>
              <div className="opt-row">
                {group.options.map((option) => (
                  <button
                    className={picked.includes(optionKey(option)) ? 'opt on' : 'opt'}
                    type="button"
                    key={optionKey(option)}
                    onClick={() => toggle(option)}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
        <div className="sheet-foot">
          <button
            className="btn btn-grad btn-lg"
            type="button"
            disabled={picked.length === 0 || addMutation.isPending}
            onClick={() => addMutation.mutate()}
          >
            {addMutation.isPending ? '保存中…' : '保存'}
          </button>
        </div>
      </div>
    </div>
  )
}

/** 与 prototype/mine.html 一致：今天登录显示时刻，否则显示日期。 */
function profileSubtitle(loginAt: string | null): string {
  if (!loginAt) return '登录态长期保持'
  const date = new Date(loginAt)
  if (Number.isNaN(date.getTime())) return '登录态长期保持'
  const pad = (value: number) => String(value).padStart(2, '0')
  const time = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  const now = new Date()
  const sameDay =
    date.getFullYear() === now.getFullYear() &&
    date.getMonth() === now.getMonth() &&
    date.getDate() === now.getDate()
  return sameDay
    ? `今天 ${time} 登录 · 登录态长期保持`
    : `${date.getMonth() + 1} 月 ${date.getDate()} 日 登录 · 登录态长期保持`
}