import { LoginForm } from './state/LoginForm';
import { Workspace } from './Workspace';
import { useSession } from './state/session';

export function App() {
  const { session, signOut } = useSession();
  if (!session) {
    return <LoginForm />;
  }
  return (
    <div className="shell">
      <a className="skip" href="#alerts-heading" onClick={() => document.getElementById('alerts-heading')?.focus()}>
        Skip to the alerts
      </a>
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
      <main id="main" tabIndex={-1}>
        <Workspace />
      </main>
    </div>
  );
}
