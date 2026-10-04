import { useState } from 'react'
import type { CSSProperties } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { StatusBar } from '../components/StatusBar'
import { Icon } from '../components/Icon'
import { createGuestSession } from '../api/session'
import { useSessionStore } from '../store/session'
import { showToast } from '../store/ui'

/** 封面登录（Shell 变体 C）。创建游客身份 → 首页，Toast「已为你创建演示身份」。 */
export function Welcome() {
  const navigate = useNavigate()
  const sessionId = useSessionStore((state) => state.sessionId)
  const setSession = useSessionStore((state) => state.setSession)
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)

  // 登录态长期保持：已有会话直接进首页（原型行为一致）。
  if (sessionId) return <Navigate to="/home" replace />

  const handleLogin = async () => {
    if (pending) return
    setPending(true)
    setFailed(false)
    try {
      const res = await createGuestSession()
      setSession({ sessionId: res.sessionId, nickname: res.nickname, loginAt: res.loginAt })
      showToast('已为你创建演示身份')
      navigate('/home', { replace: true })
    } catch {
      // 失败时不写入任何本地登录态，保持可重试的干净状态。
      setPending(false)
      setFailed(true)
    }
  }

  return (
    <div className="app app--cover">
      <StatusBar warm />

      <main className="screen screen--flush">
        <div className="cover">
          <div className="cover-hero stagger">
            <div className="brand-mark">
              <Icon name="sparkles" size={32} />
            </div>
            <h1 className="brand-name">MealMind</h1>
            <p className="brand-slogan">会打分的点餐搭子</p>
          </div>

          <div className="cover-feats stagger">
            <div className="feat">
              <span className="feat-ico" style={tint('#FFEAE0', '#FF5000')}>
                <Icon name="message-circle" size={19} />
              </span>
              <span>
                <h4>一句话说需求</h4>
                <p>不知道吃什么？说出口味、预算和忌口就行</p>
              </span>
            </div>
            <div className="feat">
              <span className="feat-ico" style={tint('#FFF0F3', '#F0436E')}>
                <Icon name="sparkles" size={19} />
              </span>
              <span>
                <h4>每道菜逐项打分</h4>
                <p>口味、预算、时效、口碑，每一项都有依据</p>
              </span>
            </div>
            <div className="feat">
              <span className="feat-ico" style={tint('#EAF7EE', '#1E9E57')}>
                <Icon name="shopping-cart" size={19} />
              </span>
              <span>
                <h4>选好直接加购</h4>
                <p>演示链路到加购为止，不做结算</p>
              </span>
            </div>
          </div>

          <div className="cover-cta stagger">
            <button className="btn btn-grad btn-lg" type="button" disabled={pending} onClick={handleLogin}>
              {pending ? '正在进入…' : failed ? '重试登录' : '游客登录，立即体验'}
            </button>
            <p className="cover-note">
              {failed ? '登录失败，请检查网络后重试' : '无需注册 · 登录状态长期保持，可随时退出'}
            </p>
          </div>
        </div>
      </main>

      <footer className="cover-foot">模拟数据 · 仅用于产品演示</footer>
    </div>
  )
}

/** 分类 / 特性图标底色（契约 token 之外的一次性 tint，仅由调用方内联传入）。 */
function tint(background: string, color: string): CSSProperties {
  return { '--tint': background, '--ink': color } as CSSProperties
}
