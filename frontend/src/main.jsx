import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App.jsx";
import { AuthProvider } from "./auth/AuthContext.jsx";
import { TrafficLogProvider } from "./components/TrafficLog.jsx";
import "./styles/app.css";

ReactDOM.createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <BrowserRouter>
      <TrafficLogProvider>
        <AuthProvider>
          <App />
        </AuthProvider>
      </TrafficLogProvider>
    </BrowserRouter>
  </React.StrictMode>
);
