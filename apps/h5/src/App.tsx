import { Navigate, Route, Routes } from 'react-router-dom'
import { ToastHost } from './components/ToastHost'
import { RequireSession } from './components/RequireSession'
import { Welcome } from './pages/Welcome'
import { Home } from './pages/Home'
import { Merchant } from './pages/Merchant'
import { Cart } from './pages/Cart'
import { Agent } from './pages/Agent'
import { Eval } from './pages/Eval'
import { Mine } from './pages/Mine'
import { Search } from './pages/Search'

/** 路由表：封面 / 首页 / 商家详情 / 购物车 / AI 会话 / 我的；其余路径回封面。 */
function App() {
  return (
    <>
      <Routes>
        <Route path="/" element={<Welcome />} />
        <Route
          path="/home"
          element={
            <RequireSession>
              <Home />
            </RequireSession>
          }
        />
        <Route
          path="/merchant/:id"
          element={
            <RequireSession>
              <Merchant />
            </RequireSession>
          }
        />
        <Route
          path="/search"
          element={
            <RequireSession>
              <Search />
            </RequireSession>
          }
        />
        <Route
          path="/cart"
          element={
            <RequireSession>
              <Cart />
            </RequireSession>
          }
        />
        <Route
          path="/agent"
          element={
            <RequireSession>
              <Agent />
            </RequireSession>
          }
        />
        <Route
          path="/eval"
          element={
            <RequireSession>
              <Eval />
            </RequireSession>
          }
        />
        <Route
          path="/mine"
          element={
            <RequireSession>
              <Mine />
            </RequireSession>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <ToastHost />
    </>
  )
}

export default App
