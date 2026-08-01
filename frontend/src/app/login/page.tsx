import { PageHeader, Panel, SectionHeader } from "@/design-system/components";
import { LoginForm } from "@/features/session/login-form";

export const dynamic = "force-dynamic";
export const revalidate = 0;

const DEMO_PERSONAS = [
  { username: "operator-1", password: "accountshield-demo-operator", role: "SECURITY_OPERATOR" },
  { username: "analyst-1", password: "accountshield-demo-analyst", role: "SIMULATION_ANALYST" },
  { username: "admin-1", password: "accountshield-demo-admin", role: "POLICY_ADMIN" },
  { username: "reader-1", password: "accountshield-demo-reader", role: "OBSERVABILITY_READER" },
] as const;

export default function LoginPage() {
  return (
    <main className="loginPage" id="main-content">
      <PageHeader
        description="Demo credentials only -- this is not a real identity provider (see ADR 0046). No end-user or production account can sign in here."
        eyebrow="Security operations"
        title="Operator sign in"
      />

      <LoginForm />

      <Panel className="loginDemoCredentials">
        <SectionHeader
          description="Fixed, publicly documented demo personas, one per operator-console role. Passwords are published here on purpose -- they are not secrets."
          title="Demo credentials"
        />
        <table className="dataTable">
          <caption className="srOnly">Demo operator credentials</caption>
          <thead>
            <tr>
              <th scope="col">Username</th>
              <th scope="col">Password</th>
              <th scope="col">Role</th>
            </tr>
          </thead>
          <tbody>
            {DEMO_PERSONAS.map((persona) => (
              <tr key={persona.username}>
                <td>{persona.username}</td>
                <td>{persona.password}</td>
                <td>{persona.role}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </Panel>
    </main>
  );
}
