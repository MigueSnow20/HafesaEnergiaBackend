FROM mcr.microsoft.com/playwright:v1.61.1-noble

WORKDIR /app

ENV NODE_ENV=production

COPY package.json package-lock.json ./

RUN npm ci

RUN npx playwright install --with-deps chromium

COPY . .

EXPOSE 3000

CMD ["npm", "start"]