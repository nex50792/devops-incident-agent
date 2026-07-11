/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  env: {
    NEXT_PUBLIC_AGENT_API: process.env.NEXT_PUBLIC_AGENT_API ?? 'http://localhost:8081',
    NEXT_PUBLIC_DEMO_API: process.env.NEXT_PUBLIC_DEMO_API ?? 'http://localhost:8082',
  },
};

export default nextConfig;
