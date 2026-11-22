import { LoginForm } from './state/LoginForm';
import { useSession } from './state/session';

export function App() {
  const { session, signOut } = useSession();
  if (!session) {
    return <LoginForm />;
  }
  return (
    <div className="shell">
      <header className="topbar">
        <h1>Alert console</h1>
        <div className="who">
          <span>
            Signed in as <strong>{session.username}</strong> ({session.role.toLowerCase()})
          </span>
          <button type="button" onClick={signOut}>
            Sign out
          </button>
        </div>
      </header>
      <main id="main" tabIndex={-1} className="workspace">
        <p>The map and the alert panel come next.</p>
      </main>
    </div>
  );
}
