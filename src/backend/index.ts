import { supabase } from './client';
import { createServices } from './services';
export { configuration } from './client';
export const services = supabase ? createServices(supabase) : null;
