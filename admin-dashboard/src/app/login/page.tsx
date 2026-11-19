import { notFound } from 'next/navigation';
import { configuration } from '@/lib/server';
import { Login } from '@/components/Login';
export default async function Page({
  searchParams,
}: {
  searchParams: Promise<{ revocation?: string }>;
}) {
  try {
    configuration();
  } catch {
    notFound();
  }
  return <Login revocationUnconfirmed={(await searchParams).revocation === 'unconfirmed'} />;
}
