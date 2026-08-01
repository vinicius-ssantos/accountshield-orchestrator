import type { Metadata } from "next";

import { SessionProvider } from "@/features/session/session-context";
import { WebVitalsReporter } from "@/features/telemetry/web-vitals-reporter";

import "./globals.css";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export const metadata: Metadata = {
  title: "AccountShield Security Operations Console",
  description: "Investigate account-protection decisions, recoveries, policies, and replay results.",
  robots: {
    index: false,
    follow: false,
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en">
      <body>
        <WebVitalsReporter />
        <SessionProvider>{children}</SessionProvider>
      </body>
    </html>
  );
}
