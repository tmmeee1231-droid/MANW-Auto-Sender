FROM node:20-alpine
ENV NODE_ENV=production
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev
COPY web ./web
RUN mkdir -p /data/media
ENV PORT=8080
EXPOSE 8080
CMD ["npm","start"]
