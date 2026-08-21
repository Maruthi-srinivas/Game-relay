import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth/AuthContext.jsx";
import { ChatProvider } from "./chat/ChatContext.jsx";
import ChatPage from "./pages/ChatPage.jsx";
import LoginPage from "./pages/LoginPage.jsx";
import RegisterPage from "./pages/RegisterPage.jsx";

function GuestOnly({ children }) {
  const { session } = useAuth();
  if (session) {
    return <Navigate to="/" replace />;
  }
  return children;
}

function ProtectedLayout() {
  const { session } = useAuth();
  if (!session) {
    return <Navigate to="/login" replace />;
  }
  return (
    <ChatProvider>
      <Outlet />
    </ChatProvider>
  );
}

export default function App() {
  return (
    <Routes>
      <Route
        path="/login"
        element={
          <GuestOnly>
            <LoginPage />
          </GuestOnly>
        }
      />
      <Route
        path="/register"
        element={
          <GuestOnly>
            <RegisterPage />
          </GuestOnly>
        }
      />
      <Route element={<ProtectedLayout />}>
        <Route path="/" element={<ChatPage />} />
        <Route path="/rooms/:roomId" element={<ChatPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
