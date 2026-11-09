import type { Talent, Category } from '../talent';
import type { CategoryRow, PortfolioAsset, TalentProfile } from './types';
export const categoryNames: Record<string, Category> = {
  actors: 'Actors',
  models: 'Models',
  creators: 'Creators',
  musicians: 'Musicians',
  photographers: 'Photographers',
};
/** Bounded offset traversal; fail visibly instead of silently truncating or looping on a broken server. */
export async function collectPages<T extends { id: string }>(
  page: (offset: number) => Promise<T[] | null>,
  size = 200,
  maximumOffset = 100000,
): Promise<T[]> {
  const rows = new Map<string, T>();
  for (let offset = 0; offset <= maximumOffset; offset += size) {
    const next = (await page(offset)) || [];
    let added = 0;
    for (const row of next) {
      if (!rows.has(row.id)) added++;
      rows.set(row.id, row);
    }
    if (next.length < size) return [...rows.values()];
    if (!added) throw new Error('Catalog pagination did not advance. Please retry.');
  }
  throw new Error('Catalog is too large to load completely. Please retry later.');
}
export async function loadPagesThrough<T extends { id: string }>(
  page: (offset: number) => Promise<T[] | null>,
  offset: number,
  size = 100,
): Promise<{ rows: T[]; hasMore: boolean }> {
  const rows = new Map<string, T>();
  let lastSize = 0;
  for (let at = 0; at <= offset; at += size) {
    const next = (await page(at)) || [];
    lastSize = next.length;
    for (const row of next) rows.set(row.id, row);
    if (next.length < size) break;
  }
  return { rows: [...rows.values()], hasMore: lastSize === size };
}
export function supportedCategories(rows: CategoryRow[]): Category[] {
  return [
    'All talent',
    ...rows.flatMap((row) => (categoryNames[row.id] ? [categoryNames[row.id]] : [])),
  ];
}
export async function mapTalent(
  profile: TalentProfile,
  assets: PortfolioAsset[],
  signedUrl: (path: string) => Promise<string>,
): Promise<{ talent: Talent; mediaUnavailable: boolean }> {
  let mediaUnavailable = false;
  const sign = async (path: string) => {
    try {
      return await signedUrl(path);
    } catch {
      mediaUnavailable = true;
      return undefined;
    }
  };
  const portraitUri = profile.portrait_path ? await sign(profile.portrait_path) : undefined;
  const portfolio = await Promise.all(
    assets
      .filter((a) => a.talent_id === profile.id)
      .map(async (a) => ({ image: a.id, label: a.label, uri: await sign(a.path) })),
  );
  return {
    talent: {
      id: profile.id,
      verifiedCreator: profile.verified_creator === true,
      name: profile.display_name,
      city: profile.city,
      category: categoryNames[profile.category_id] || 'Creators',
      rate: profile.day_rate,
      bio: profile.bio,
      skills: profile.skills,
      available: profile.available,
      portrait: '',
      portraitUri,
      portfolio,
      remote: true,
    },
    mediaUnavailable,
  };
}
