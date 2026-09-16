import { Routes, Route } from 'react-router';
import { Layout } from './components/Layout';
import { IndexRoute } from './pages/IndexRoute';
import { CasesPage } from './pages/CasesPage';
import { CaseDetailPage } from './pages/CaseDetailPage';
import { ConfigPage } from './pages/ConfigPage';

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        {/* The root only decides: landing on an empty board, else /cases. The
            board owns its own URL so deep links are never ambiguous. */}
        <Route index element={<IndexRoute />} />
        <Route path="cases" element={<CasesPage />} />
        <Route path="config" element={<ConfigPage />} />
        <Route path=":id" element={<CaseDetailPage />} />
      </Route>
    </Routes>
  );
}
