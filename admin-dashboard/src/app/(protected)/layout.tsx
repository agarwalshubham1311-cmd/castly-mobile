import { cookies } from 'next/headers';
import { redirect, notFound } from 'next/navigation';
import { configuration, ACCESS, SESSION } from '@/lib/server';
import { AdminShell } from '@/components/AdminShell';
export default async function Layout({ children }: { children: React.ReactNode }) {
  try {
    configuration();
  } catch {
    notFound();
  }
  const jar = await cookies();
  if (!jar.get(ACCESS) || !jar.get(SESSION)) redirect('/login');
  return <AdminShell>{children}</AdminShell>;
}
