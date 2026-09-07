import { Route, Routes } from 'react-router-dom';
import { AuthProvider } from '../auth/AuthContext';
import { ToastProvider } from '../components/Toast';
import { LoginPage } from '../auth/LoginPage';
import { CallbackPage } from '../auth/CallbackPage';
import { AppShell } from './AppShell';
import { AuthGuard } from './AuthGuard';
import { DashboardPage } from '../pages/DashboardPage';
import { AccountDetailPage } from '../pages/accounts/AccountDetailPage';
import { PaymentsPage } from '../pages/payments/PaymentsPage';
import { MandatesPage } from '../pages/mandates/MandatesPage';
import { R2pInboxPage } from '../pages/r2p/R2pInboxPage';
import { BeneficiariesPage } from '../pages/beneficiaries/BeneficiariesPage';
import { ConsentsPage } from '../pages/consents/ConsentsPage';
import { NotificationsPage } from '../pages/notifications/NotificationsPage';
import { ProfilePage } from '../pages/profile/ProfilePage';
import { PrivacyPage } from '../pages/privacy/PrivacyPage';

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
                        <Route path="accounts/:accountId" element={<AccountDetailPage />} />
                        <Route path="payments" element={<PaymentsPage />} />
                        <Route path="mandates" element={<MandatesPage />} />
                        <Route path="r2p" element={<R2pInboxPage />} />
                        <Route path="beneficiaries" element={<BeneficiariesPage />} />
                        <Route path="consents" element={<ConsentsPage />} />
                        <Route path="notifications" element={<NotificationsPage />} />
                        <Route path="profile" element={<ProfilePage />} />
                        <Route path="privacy" element={<PrivacyPage />} />
                    </Route>
                </Routes>
            </ToastProvider>
        </AuthProvider>
    );
}
