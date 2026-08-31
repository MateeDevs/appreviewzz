import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useMe } from './api/hooks'
import {
  AcceptInvitationPage,
  ForgotPasswordPage,
  LoginPage,
  RegisterPage,
  ResetPasswordPage,
  VerifyEmailPage,
} from './pages/Auth'
import { AppDetailPage, AppsPage } from './pages/Apps'
import { AuditPage, TeamPage } from './pages/Team'
import { DashboardPage } from './pages/Dashboard'
import { InboxPage } from './pages/Inbox'
import { OnboardingPage } from './pages/Onboarding'
import { OrgLayout } from './pages/OrgLayout'
import { OrganizationsPage } from './pages/Organizations'
import { PlatformPage } from './pages/Platform'
import { Loading } from './components/ui'

export function App() {
  const me = useMe()
  const location = useLocation()

  if (me.isPending) {
    return (
      <div className="center">
        <Loading />
      </div>
    )
  }

  const signedIn = me.data != null

  return (
    <Routes>
      <Route path="/login" element={signedIn ? <Navigate to="/" replace /> : <LoginPage />} />
      <Route path="/registrace" element={signedIn ? <Navigate to="/" replace /> : <RegisterPage />} />
      <Route path="/overeni" element={<VerifyEmailPage />} />
      <Route path="/zapomenute-heslo" element={<ForgotPasswordPage />} />
      <Route path="/obnova-hesla" element={<ResetPasswordPage />} />
      <Route path="/pozvanka" element={<AcceptInvitationPage signedIn={signedIn} />} />

      {!signedIn ? (
        // Adresu si pamatujeme, ať se po přihlášení člověk vrátí tam, kam mířil.
        <Route path="*" element={<Navigate to={`/login?next=${encodeURIComponent(location.pathname)}`} replace />} />
      ) : (
        <>
          <Route path="/organizace" element={<OrganizationsPage />} />
          {/* Routa se vykresluje jen správci platformy; o přístupu ale rozhoduje server —
              tohle je zkratka, aby se odsud nekoukalo na samé chyby. */}
          {me.data?.platformRole === 'SUPERADMIN' ? (
            <Route path="/platforma" element={<PlatformPage />} />
          ) : null}
          <Route path="/" element={<HomeRedirect />} />
          <Route path="/:org" element={<OrgLayout />}>
            <Route index element={<DashboardPage />} />
            <Route path="onboarding" element={<OnboardingPage />} />
            <Route path="recenze" element={<InboxPage />} />
            <Route path="aplikace" element={<AppsPage />} />
            <Route path="aplikace/:appId" element={<AppDetailPage />} />
            <Route path="tym" element={<TeamPage />} />
            <Route path="audit" element={<AuditPage />} />
          </Route>
        </>
      )}
    </Routes>
  )
}

/** Kdo má jednu organizaci, nechce rozcestník; kdo žádnou, potřebuje ji založit. */
function HomeRedirect() {
  const me = useMe()
  const organizations = me.data?.organizations ?? []
  if (organizations.length === 1 && organizations[0]) return <Navigate to={`/${organizations[0].slug}`} replace />
  return <Navigate to="/organizace" replace />
}
