# Use an official base image
FROM python:3.12-alpine

# Set the working directory to /app
WORKDIR /app

# Copy the application code into the container (.dockerignore keeps .env and data out)
COPY . /app/

# Files live in /data; login comes from YCC_USER / YCC_PASSWORD
ENV YCC_ROOT=/data YCC_PORT=47490 YCC_USER=ycc
VOLUME /data

# Expose the port the app will use
EXPOSE 47490

# Run the command to start the app when the container launches
CMD ["python3", "-u", "/app/ycc_dav.py"]
