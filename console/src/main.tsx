import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter } from 'react-router-dom'
import { App } from './App'
import { ensureCsrf } from './api/client'
import './styles.css'

/**
 * CSRF token si vyzvedneme dřív, než se ukáže první formulář — server bez něj odmítne
 * i přihlášení (a je to tak správně: chrání to i proti přihlášení oběti na cizí účet).
 */
void ensureCsrf()

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Console je nástroj na práci, ne dashboard na zdi: data se čtou při návratu do okna,
      // ne v nekonečné smyčce.
      refetchOnWindowFocus: true,
      staleTime: 15_000,
      retry: 1,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
