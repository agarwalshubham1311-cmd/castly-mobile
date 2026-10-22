export const categories = [
  'All talent',
  'Actors',
  'Models',
  'Creators',
  'Musicians',
  'Photographers',
] as const;
export type Category = (typeof categories)[number];
export type Talent = {
  id: string;
  name: string;
  category: Category;
  city: string;
  rate: number;
  portrait: string;
  portraitUri?: string;
  remote?: boolean;
  portfolio: { image: string; label: string; uri?: string }[];
  available: boolean;
  bio: string;
  skills: string[];
};
export const talents: Talent[] = [
  {
    id: 'aria',
    portrait: 'portrait-01',
    portfolio: [
      { image: 'portrait-02', label: 'Stock portrait study 2' },
      { image: 'portrait-03', label: 'Stock portrait study 3' },
    ],
    name: 'Aria Kapoor',
    category: 'Actors',
    city: 'Mumbai',
    rate: 18000,
    available: true,
    bio: 'An expressive performer bringing warmth and authenticity to every frame. Experienced in independent cinema, brand films and editorial storytelling.',
    skills: ['Acting', 'Brand films', 'Hindi', 'English'],
  },
  {
    id: 'rehan',
    portrait: 'portrait-04',
    portfolio: [
      { image: 'portrait-05', label: 'Stock portrait study 5' },
      { image: 'portrait-06', label: 'Stock portrait study 6' },
    ],
    name: 'Rehan Mehta',
    category: 'Models',
    city: 'Delhi',
    rate: 15000,
    available: true,
    bio: 'A versatile fashion and commercial model with an eye for movement. Creating memorable imagery for emerging labels and established brands.',
    skills: ['Editorial', 'Runway', 'Commercial'],
  },
  {
    id: 'maya',
    portrait: 'portrait-08',
    portfolio: [{ image: 'portrait-07', label: 'Stock portrait study 7' }],
    name: 'Maya Sen',
    category: 'Creators',
    city: 'Bengaluru',
    rate: 12000,
    available: false,
    bio: 'A visual storyteller exploring style, travel and everyday inspiration. Thoughtful content with a distinctive creative voice.',
    skills: ['Lifestyle', 'Video', 'Photography'],
  },
  {
    id: 'ishaan',
    portrait: 'portrait-09',
    portfolio: [
      { image: 'portrait-10', label: 'Stock portrait study 10' },
      { image: 'portrait-11', label: 'Stock portrait study 11' },
    ],
    name: 'Ishaan Roy',
    category: 'Musicians',
    city: 'Mumbai',
    rate: 22000,
    available: true,
    bio: 'Singer, songwriter and live performer blending soulful melodies with contemporary production. Available for creative collaborations and live sessions.',
    skills: ['Vocals', 'Guitar', 'Live performance'],
  },
];
export type Filters = {
  city: string;
  maxRate: number;
  availableOnly: boolean;
  minRate?: number;
  skills?: string[];
};
export const defaultFilters: Filters = { city: 'All cities', maxRate: 30000, availableOnly: false };
export function findTalents(
  query: string,
  category: Category,
  filters: Filters,
  source: Talent[] = talents,
): Talent[] {
  const term = query.trim().toLowerCase();
  return source.filter(
    (t) =>
      (category === 'All talent' || t.category === category) &&
      (!term || [t.name, t.city, t.category, ...t.skills].join(' ').toLowerCase().includes(term)) &&
      (filters.city === 'All cities' || t.city === filters.city) &&
      t.rate >= (filters.minRate ?? 0) &&
      t.rate <= filters.maxRate &&
      (!filters.skills?.length ||
        filters.skills.some((skill) =>
          t.skills.some((s) => s.toLowerCase().includes(skill.toLowerCase())),
        )) &&
      (!filters.availableOnly || t.available),
  );
}
export const money = (value: number) => `₹${value.toLocaleString('en-IN')}`;

export type SortOrder = 'Recommended' | 'Price: low to high' | 'Price: high to low' | 'Name';
export function searchTalents(
  query: string,
  category: Category,
  filters: Filters,
  sort: SortOrder,
  source: Talent[] = talents,
): Talent[] {
  const results = findTalents(query, category, filters, source);
  return sort === 'Price: low to high'
    ? results.sort((a, b) => a.rate - b.rate)
    : sort === 'Price: high to low'
      ? results.sort((a, b) => b.rate - a.rate)
      : sort === 'Name'
        ? results.sort((a, b) => a.name.localeCompare(b.name))
        : results;
}
