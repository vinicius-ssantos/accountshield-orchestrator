import type { Metadata } from "next";
import "./globals.css";

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
      <body>{children}</body>
    </html>
  );
}
