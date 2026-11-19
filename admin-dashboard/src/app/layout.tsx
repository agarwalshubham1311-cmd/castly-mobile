import { headers } from 'next/headers';
import type { Metadata } from 'next';
import './globals.css';
export const metadata: Metadata = {
  title: 'Castly Operations',
  description: 'Restricted Castly administration',
  robots: { index: false, follow: false },
};
export default async function RootLayout({ children }: { children: React.ReactNode }) {
  await headers();
  return (
    <html lang="en">
      <body>
        <a className="skip" href="#main">
          Skip to content
        </a>
        {children}
      </body>
    </html>
  );
}
