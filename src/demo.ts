export type Booking = {
  id: string;
  talentId: string;
  project: string;
  date: string;
  location: string;
  requirements: string;
  budget: string;
  status: 'Pending' | 'Confirmed' | 'Completed';
  local?: boolean;
};
export const demoBookings: Booking[] = [
  {
    id: 'sample-1',
    talentId: 'aria',
    project: 'Brand photoshoot',
    date: '2026-11-24',
    location: 'Mumbai',
    requirements: 'Editorial portraits for a sample lifestyle campaign.',
    budget: '18000',
    status: 'Pending',
  },
  {
    id: 'sample-2',
    talentId: 'rehan',
    project: 'Fashion editorial',
    date: '2026-12-12',
    location: 'Delhi',
    requirements: 'A fictional studio shoot for the demo.',
    budget: '15000',
    status: 'Confirmed',
  },
  {
    id: 'sample-3',
    talentId: 'ishaan',
    project: 'Live performance',
    date: '2026-10-12',
    location: 'Mumbai',
    requirements: 'Sample acoustic showcase.',
    budget: '22000',
    status: 'Completed',
  },
];
export type Message = { id: string; text: string; mine: boolean };
export type Conversation = {
  id: string;
  talentId: string;
  unread: boolean;
  archived: boolean;
  booking: boolean;
  messages: Message[];
};
export const demoConversations: Conversation[] = [
  {
    id: 'aria',
    talentId: 'aria',
    unread: true,
    archived: false,
    booking: true,
    messages: [
      { id: 'a1', text: 'Hi! I’d love to hear more about your creative brief.', mine: false },
    ],
  },
  {
    id: 'rehan',
    talentId: 'rehan',
    unread: false,
    archived: false,
    booking: false,
    messages: [
      { id: 'r1', text: 'Thanks for sharing the moodboard. Beautiful direction!', mine: false },
    ],
  },
  {
    id: 'maya',
    talentId: 'maya',
    unread: true,
    archived: false,
    booking: false,
    messages: [{ id: 'm1', text: 'Let’s explore the concept together.', mine: false }],
  },
  {
    id: 'ishaan',
    talentId: 'ishaan',
    unread: false,
    archived: true,
    booking: true,
    messages: [{ id: 'i1', text: 'A lovely idea for an acoustic evening.', mine: false }],
  },
];
export type BookingForm = {
  project: string;
  date: string;
  location: string;
  requirements: string;
  budget: string;
};
export function validateBooking(form: BookingForm, today = new Date().toISOString().slice(0, 10)) {
  const errors: Partial<Record<keyof BookingForm, string>> = {};
  if (!form.project.trim()) errors.project = 'Choose a project type.';
  const parsed = new Date(`${form.date}T12:00:00Z`);
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(form.date) ||
    !Number.isFinite(parsed.getTime()) ||
    parsed.toISOString().slice(0, 10) !== form.date ||
    form.date < today
  )
    errors.date = 'Enter a valid future date as YYYY-MM-DD.';
  if (!form.location.trim()) errors.location = 'Enter a location.';
  if (form.requirements.trim().length < 20)
    errors.requirements = 'Describe your project in at least 20 characters.';
  if (!form.budget.trim() || !Number.isFinite(Number(form.budget)) || Number(form.budget) <= 0)
    errors.budget = 'Enter a positive indicative budget.';
  return errors;
}
export function parseSaved(value: string | null, known: string[]): string[] {
  if (!value) return [];
  const parsed: unknown = JSON.parse(value);
  if (!Array.isArray(parsed)) return [];
  return [
    ...new Set(parsed.filter((id): id is string => typeof id === 'string' && known.includes(id))),
  ];
}
