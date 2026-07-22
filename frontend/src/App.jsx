import { BrowserRouter, Routes, Route, Navigate, useNavigate } from "react-router-dom";
import { StompSessionProvider } from "react-stomp-hooks";
import Sign from './pages/Sign';
import View from './pages/View';
import Edit from './pages/edit/Edit'
import ScrollToTop from "./utils/ScrollToTop.jsx";
import { useState } from "react";
import { WS_URL, getToken } from "./api.js";

function App() {
    const [username, setUsername] = useState('');
    const [, setLoggedin] = useState(false);

    return (
        <BrowserRouter>
            <ScrollToTop />
            <Routes>
                <Route path="/" element={<Sign setUsername={setUsername} username={username} setLoggedin={setLoggedin} />} />
                <Route path="/view" element={<RequireAuth><View /></RequireAuth>} />
                <Route path={'/edit/:docId'} element={<RequireAuth><EditWrapper /></RequireAuth>} />
            </Routes>
        </BrowserRouter>
    )
}

// Gate the authenticated routes: without a token, bounce to the login screen instead of rendering a
// page whose API calls would all fail.
function RequireAuth({ children }) {
    return getToken() ? children : <Navigate to="/" replace />;
}

function EditWrapper() {
    const navigate = useNavigate();

    return (
        <StompSessionProvider url={WS_URL}
            connectHeaders={{ "Authorization": getToken() }}
            onDisconnect={() => navigate('/view')}>
            <Edit />
        </StompSessionProvider>
    );
}

export default App
