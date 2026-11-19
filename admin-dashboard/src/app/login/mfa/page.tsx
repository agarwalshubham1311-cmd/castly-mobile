import { notFound } from 'next/navigation';
import { configuration } from '@/lib/server';
import { Login } from '@/components/Login';
export default function Page() {
  try {
    configuration();
  } catch {
    notFound();
  }
  return <Login mfa />;
}
