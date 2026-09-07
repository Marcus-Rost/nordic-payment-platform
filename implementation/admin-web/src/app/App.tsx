import { Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../auth/AuthContext';
import { ToastProvider } from '../components/Toast';
import { LoginPage } from '../auth/LoginPage';
import { CallbackPage } from '../auth/CallbackPage';
import { AppShell } from './AppShell';
import { AuthGuard } from './AuthGuard';
import { DashboardPage } from '../pages/DashboardPage';
import { FraudQueuePage } from '../pages/fraud/FraudQueuePage';
import { FraudCaseDetailPage } from '../pages/fraud/FraudCaseDetailPage';
import { SanctionsQueuePage } from '../pages/sanctions/SanctionsQueuePage';
import { SanctionsHitDetailPage } from '../pages/sanctions/SanctionsHitDetailPage';
import { AccountSearchPage } from '../pages/accounts/AccountSearchPage';
import { AccountDetailPage } from '../pages/accounts/AccountDetailPage';
import { AuditSearchPage } from '../pages/audit/AuditSearchPage';
import { SystemHealthPage } from '../pages/system/SystemHealthPage';
import { UsersAdminPage } from '../pages/users/UsersAdminPage';

export default function App() {
    return (
        <AuthProvider>
            <ToastProvider>
                <Routes>
                    <Route path="/login" element={<LoginPage />} />
                    <Route path="/oauth/callback" element={<CallbackPage />} />
                    <Route
                        element={
                            <AuthGuard>
                                <AppShell />
                            </AuthGuard>
                        }
                    >
                        <Route index element={<DashboardPage />} />
                        <Route path="fraud" element={<FraudQueuePage />} />
                        <Route path="fraud/:caseId" element={<FraudCaseDetailPage />} />
                        <Route path="sanctions" element={<SanctionsQueuePage />} />
                        <Route path="sanctions/:hitId" element={<SanctionsHitDetailPage />} />
                        <Route path="accounts" element={<AccountSearchPage />} />
                        <Route path="accounts/:accountId" element={<AccountDetailPage />} />
                        <Route path="audit" element={<AuditSearchPage />} />
                        <Route path="system" element={<SystemHealthPage />} />
                        <Route path="users" element={<UsersAdminPage />} />
                    </Route>
                </Routes>
            </ToastProvider>
        </AuthProvider>
    );
}
